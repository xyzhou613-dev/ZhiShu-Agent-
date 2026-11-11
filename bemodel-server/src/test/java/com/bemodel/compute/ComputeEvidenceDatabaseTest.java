package com.bemodel.compute;

import com.bemodel.common.BizException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;

import static com.bemodel.compute.ComputeReleaseBridgeTest.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="COMPUTE_ONTOLOGY_MYSQL_URL", matches="jdbc:mysql:.*")
class ComputeEvidenceDatabaseTest {
    static final com.fasterxml.jackson.databind.ObjectMapper JSON = ComputeRuleEvaluatorTest.JSON;
    static ObjectNode request() { return JSON.createObjectNode().put("nodeKey", "a800-demo").put("requestId", 1); }

    static ComputeEvidenceService service(ComputeMysqlTestDatabase db, ComputeReleaseBridge bridge) {
        return new ComputeEvidenceService(new ComputeDatabase.Store(db.business(), null),
                new ComputeRuleRunner(bridge, new ComputeRuleService(null, JSON), JSON), JSON);
    }

    private ComputeEvidenceService.Detail capture(ComputeMysqlTestDatabase db, ComputeEvidenceService service,
                                                ComputeRuleRunner runner, String asOf) {
        var tx = new TransactionTemplate(new DataSourceTransactionManager(db.business().getDataSource()));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx.execute(status -> service.capture(request(), runner.pinActive(), Instant.parse(asOf)));
    }

    static void nodeObservation(ComputeMysqlTestDatabase db) {
        db.business().update("""
                INSERT INTO resource_observation(node_id,source_subject_kind,source_subject_key,subject_kind,subject_id,
                    source,observed_at,valid_until,status,values_json)
                VALUES(1,'NODE','a800-demo','NODE',1,'A800_DEMO_FIXTURE','2026-11-06 10:00:00',
                    '2026-11-06 10:01:00','OK','{"memoryUsedBytes":100,"temporaryUsedBytes":0,"persistentUsedBytes":0}')
                """);
    }

    @Test void upgradesCapturesAndProtectsHistoricalEvidenceAndRollsBackPartialWrites() throws Exception {
        try (var db = ComputeMysqlTestDatabase.create("5")) {
            var migrate = Flyway.configure().dataSource(db.businessUrl(), ComputeMysqlTestDatabase.username(),
                    ComputeMysqlTestDatabase.password()).locations("classpath:db/a800-compute").target("6").load();
            assertEquals(1, migrate.migrate().migrationsExecuted);
            var bridge = bridge(db);
            var runner = new ComputeRuleRunner(bridge, new ComputeRuleService(null, JSON), JSON);
            var service = service(db, bridge);
            assertThrows(BizException.class, () -> service.capture(request()));
            publish(db, bridge, bridge.prepare(rules()), "n03-v1");
            var missing = capture(db, service, runner, "2026-11-06T10:00:10Z");
            assertFalse(missing.snapshot().manifest().path("usable").asBoolean());
            assertTrue(missing.snapshot().manifest().path("issues").toString().contains("NODE:1:MISSING_OBSERVATION"));
            assertEquals(9, missing.evidence().size());
            nodeObservation(db);
            var original = capture(db, service, runner, "2026-11-06T10:00:10Z");
            assertTrue(original.snapshot().manifest().path("usable").asBoolean(), original.toString());
            assertFalse(original.authorizesExecution());
            assertEquals(10, original.evidence().size());
            var ledger = original.evidence().getFirst();
            assertEquals("PLATFORM_LEDGER", ledger.source());
            assertEquals(1, ledger.values().path("activeJob").path("id").asLong());
            assertEquals(2, ledger.values().path("activeJob").path("effective_gpu_count").asLong());
            assertEquals(2, ledger.values().path("holds").get(1).path("deployment_id").asLong());
            assertEquals("PENDING", ledger.values().path("allocations").get(0).path("status").asText());
            assertFalse(original.toString().contains("demo/train"));
            assertEquals(original, service.detail(original.snapshot().id()));
            assertEquals(2, service.list(1, 1).getTotal());
            assertEquals(original.snapshot().id(), service.list(1, 1).getList().getFirst().id());
            var j = db.business();
            j.update("UPDATE resource_observation SET status='ERROR',values_json=NULL WHERE subject_kind='GPU' AND subject_id=1");
            j.update("UPDATE resource_observation SET source='UNTRUSTED',values_json=JSON_OBJECT('healthy',TRUE,'occupied',TRUE,'imageRef','secret-image') WHERE subject_kind='GPU' AND subject_id=2");
            j.update("UPDATE resource_observation SET source_subject_key='wrong-gpu' WHERE subject_kind='GPU' AND subject_id=3");
            j.update("UPDATE resource_observation SET values_json=JSON_OBJECT('healthy','true','occupied',FALSE) WHERE subject_kind='GPU' AND subject_id=4");
            var invalid = capture(db, service, runner, "2026-11-06T10:00:10Z");
            assertTrue(invalid.snapshot().manifest().path("issues").toString().contains("OBSERVATION_ERROR"));
            assertTrue(invalid.snapshot().manifest().path("issues").toString().contains("SOURCE_MISMATCH"));
            assertTrue(invalid.snapshot().manifest().path("issues").toString().contains("SUBJECT_MISMATCH"));
            assertTrue(invalid.snapshot().manifest().path("issues").toString().contains("INVALID_VALUE:healthy"));
            assertFalse(invalid.toString().contains("secret-image"));
            assertEquals(original, service.detail(original.snapshot().id()));
            var current = service.capture(request());
            assertTrue(current.snapshot().asOf().isAfter(Instant.parse("2026-11-06T10:01:00Z")));
            assertTrue(current.snapshot().manifest().path("issues").toString().contains("STALE_EVIDENCE"));
            assertThrows(BizException.class, () -> service.capture(request().put("asOf", "2026-11-06T10:00:10Z")));
            assertThrows(BizException.class, () -> service.capture(request().put("requestId", 9999)));
            assertThrows(BizException.class, () -> service.capture(request().put("nodeKey", "unknown")));
            long snapshotId = original.snapshot().id(), evidenceId = ledger.id();
            assertThrows(Exception.class, () -> j.update("UPDATE evidence_snapshot SET as_of=UTC_TIMESTAMP(6) WHERE id=?", snapshotId));
            assertThrows(Exception.class, () -> j.update("DELETE FROM evidence_snapshot WHERE id=?", snapshotId));
            assertThrows(Exception.class, () -> j.update("UPDATE evidence SET values_json='{}' WHERE id=?", evidenceId));
            assertThrows(Exception.class, () -> j.update("DELETE FROM evidence WHERE id=?", evidenceId));
            assertThrows(Exception.class, () -> j.update("""
                    INSERT INTO evidence(snapshot_id,source,observed_at,valid_until,values_json)
                    VALUES(?,'injected',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),'{}')
                    """, snapshotId));
            long count = service.list(1, 20).getTotal();
            long entries = j.queryForObject("SELECT COUNT(*) FROM evidence", Long.class);
            j.execute("CREATE TRIGGER fail_n03_write BEFORE INSERT ON evidence FOR EACH ROW BEGIN IF NEW.observation_id=6 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected'; END IF; END");
            assertThrows(Exception.class, () -> service.capture(request()));
            assertEquals(count, service.list(1, 20).getTotal());
            assertEquals(entries, j.queryForObject("SELECT COUNT(*) FROM evidence", Long.class));
            assertEquals(0, j.queryForObject("SELECT COUNT(*) FROM evidence_snapshot WHERE sealed=FALSE", Integer.class));
            j.execute("DROP TRIGGER fail_n03_write");
            assertEquals(0, migrate.migrate().migrationsExecuted);
            assertEquals(4, j.queryForObject("SELECT COUNT(*) FROM gpu_group", Integer.class));
            assertEquals(8, j.queryForObject("SELECT COUNT(*) FROM gpu_device", Integer.class));
        }
    }
}
