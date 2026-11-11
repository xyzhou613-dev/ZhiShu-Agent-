package com.bemodel.compute;

import com.bemodel.common.BizException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static com.bemodel.compute.ComputeReleaseBridgeTest.*;
import static com.bemodel.compute.ComputeEvidenceDatabaseTest.JSON;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="COMPUTE_ONTOLOGY_MYSQL_URL", matches="jdbc:mysql:.*")
class ComputeEvidenceEvaluationDatabaseTest {
    @Test void upgradesAndEvaluatesServerEvidenceWithAtomicHistoryAndReferences() throws Exception {
        try (var db = ComputeMysqlTestDatabase.create("6")) {
            var bridge = bridge(db);
            publish(db, bridge, bridge.prepare(rules()), "n03-run-v1");
            var j = db.business();
            long legacyId = ComputeReleaseBridge.insert(j, """
                    INSERT INTO rule_evaluation_run(rule_set_id,stage,actor,evaluated_at,facts,overall_result)
                    SELECT rule_set_id,'ADMISSION','legacy',UTC_TIMESTAMP(6),'{}','ERROR' FROM active_rule_set WHERE id=1
                    """);
            j.update("""
                    INSERT INTO rule_evaluation_check(run_id,rule_set_id,rule_version_id,definition_version,result,reason_code,output)
                    SELECT ?,m.rule_set_id,m.rule_version_id,1,'ERROR','INVALID_FACTS','{}'
                    FROM rule_set_member m JOIN rule_version v ON v.id=m.rule_version_id
                    JOIN active_rule_set a ON a.rule_set_id=m.rule_set_id WHERE v.stage='ADMISSION'
                    """, legacyId);
            var migration = Flyway.configure().dataSource(db.businessUrl(), ComputeMysqlTestDatabase.username(),
                    ComputeMysqlTestDatabase.password()).locations("classpath:db/a800-compute").target("7").load();
            assertEquals(1, migration.migrate().migrationsExecuted);
            var store = new ComputeDatabase.Store(j, null);
            var runner = new ComputeRuleRunner(bridge, new ComputeRuleService(null, JSON), JSON);
            var evidence = new ComputeEvidenceService(store, runner, JSON);
            var service = new ComputeRuleEvaluationService(store, runner, JSON, evidence);
            assertEquals("CALLER", service.detail(legacyId).run().inputSource());
            assertNull(service.detail(legacyId).run().snapshotId());
            assertTrue(service.detail(legacyId).checks().getFirst().evidenceIds().isEmpty());
            ObjectNode body = ComputeEvidenceDatabaseTest.request().put("stage", "ADMISSION");
            var stale = service.execute(body);
            assertEquals("SERVER_SNAPSHOT", stale.run().inputSource());
            assertEquals(stale.run().asOf(), stale.run().evaluatedAt());
            assertEquals(stale.run().asOf().toString(), stale.facts().path("R-11").path("asOf").asText());
            assertEquals("FAIL", stale.run().overallResult().name());
            assertEquals("QUEUE_STALE_EVIDENCE", stale.checks().getLast().reasonCode());
            assertEquals(1, stale.checks().getFirst().evidenceIds().size());
            assertEquals(8, stale.checks().getLast().evidenceIds().size());
            assertFalse(stale.run().authorizesExecution());
            var submit = JSON.createObjectNode().put("employeeId", 1).put("projectId", 1).put("taskTypeId", 1)
                    .put("clientRequestKey", "n03-server-admission").put("requestedGpuCount", 1);
            submit.putObject("requestedResourceSpec").put("memoryBytes", 34359738368L).put("vramBytesPerGpu", 85899345920L);
            submit.putObject("launchSpec").put("imageRef", "demo/train");
            long requestId = new ComputeRequestService(store, JSON).submit(submit).id();
            body.put("requestId", requestId);
            ComputeEvidenceDatabaseTest.nodeObservation(db);
            var observedAt = java.time.LocalDateTime.ofInstant(java.time.Instant.now().minusSeconds(1), java.time.ZoneOffset.UTC);
            j.update("UPDATE resource_observation SET observed_at=?,valid_until=?", observedAt, observedAt.plusMinutes(10));
            var passed = service.execute(body);
            assertEquals("PASS", passed.run().overallResult().name(), passed.toString());
            assertEquals(9, passed.checks().getLast().evidenceIds().size());
            assertEquals(passed, service.detail(passed.run().id()));
            j.update("UPDATE resource_observation SET source='UNTRUSTED' WHERE subject_kind='GPU' AND subject_id=1");
            var rejected = service.execute(body);
            assertEquals("FAIL", rejected.checks().getLast().result().name());
            assertTrue(rejected.checks().getLast().output().toString().contains("SOURCE_MISMATCH"));
            publish(db, bridge, bridge.prepare(rules()), "n03-run-v2");
            assertEquals(stale, service.detail(stale.run().id()));
            assertEquals(passed, service.detail(passed.run().id()));
            var next = service.execute(body);
            assertNotEquals(passed.run().ruleSetId(), next.run().ruleSetId());
            assertEquals(next.run().ruleSetId(), next.evidenceSnapshot().snapshot().ruleSetId());
            assertThrows(Exception.class, () -> j.update("""
                    INSERT INTO rule_evaluation_check_evidence(run_id,rule_version_id,evidence_id,snapshot_id)
                    VALUES(?,?,?,?)
                    """, passed.run().id(), passed.checks().getFirst().ruleVersionId(), next.evidenceSnapshot().evidence().getFirst().id(), passed.run().snapshotId()));
            assertThrows(BizException.class, () -> service.execute(body.deepCopy().put("stage", "PLANNING")));
            assertThrows(BizException.class, () -> service.execute(body.deepCopy().put("snapshotId", passed.run().snapshotId())));
            assertThrows(BizException.class, () -> service.execute(body.deepCopy().set("facts", JSON.createObjectNode())));
            long runs = j.queryForObject("SELECT COUNT(*) FROM rule_evaluation_run", Long.class);
            long snapshots = j.queryForObject("SELECT COUNT(*) FROM evidence_snapshot", Long.class);
            long values = j.queryForObject("SELECT COUNT(*) FROM evidence", Long.class);
            long links = j.queryForObject("SELECT COUNT(*) FROM rule_evaluation_check_evidence", Long.class);
            j.execute("CREATE TRIGGER fail_n03_link BEFORE INSERT ON rule_evaluation_check_evidence FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected link failure'");
            assertThrows(Exception.class, () -> service.execute(body));
            assertEquals(runs, j.queryForObject("SELECT COUNT(*) FROM rule_evaluation_run", Long.class));
            assertEquals(snapshots, j.queryForObject("SELECT COUNT(*) FROM evidence_snapshot", Long.class));
            assertEquals(values, j.queryForObject("SELECT COUNT(*) FROM evidence", Long.class));
            assertEquals(links, j.queryForObject("SELECT COUNT(*) FROM rule_evaluation_check_evidence", Long.class));
            j.execute("DROP TRIGGER fail_n03_link");
            assertEquals("SUBMITTED", j.queryForObject("SELECT status FROM compute_request WHERE id=?", String.class, requestId));
            assertEquals(0, j.queryForObject("SELECT COUNT(*) FROM decision", Integer.class));
            assertEquals(0, migration.migrate().migrationsExecuted);
        }
    }
}
