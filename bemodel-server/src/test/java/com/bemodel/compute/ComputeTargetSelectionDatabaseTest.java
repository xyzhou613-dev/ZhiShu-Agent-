package com.bemodel.compute;

import com.bemodel.common.BizException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import static com.bemodel.compute.ComputeReleaseBridgeTest.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="COMPUTE_ONTOLOGY_MYSQL_URL",matches="jdbc:mysql:.*")
class ComputeTargetSelectionDatabaseTest {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=ComputeRuleEvaluatorTest.JSON;
    private ObjectNode body(long requestId){return JSON.createObjectNode().put("nodeKey","a800-demo").put("requestId",requestId);}
    private long submit(ComputeDatabase.Store store,int count) {
        var b=JSON.createObjectNode().put("employeeId",1).put("projectId",1).put("taskTypeId",1)
                .put("requestedGpuCount",count).put("clientRequestKey","n04-"+count);
        b.putObject("requestedResourceSpec").put("memoryBytes",34359738368L).put("vramBytesPerGpu",85899345920L);
        b.putObject("launchSpec").put("imageRef","demo/train");
        return new ComputeRequestService(store,JSON).submit(b).id();
    }

    @Test void upgradesPersistsServerSelectionsAndKeepsVersionsAndTransactionsIsolated()throws Exception {
        try(var db=ComputeMysqlTestDatabase.create("7")) {
            var migration=Flyway.configure().dataSource(db.businessUrl(),ComputeMysqlTestDatabase.username(),ComputeMysqlTestDatabase.password())
                    .locations("classpath:db/a800-compute").target("8").load();
            assertEquals(1,migration.migrate().migrationsExecuted);
            var bridge=bridge(db);var j=db.business();var store=new ComputeDatabase.Store(j,null);
            var runner=new ComputeRuleRunner(bridge,new ComputeRuleService(null,JSON),JSON);
            var evidence=new ComputeEvidenceService(store,runner,JSON);
            var service=new ComputeTargetSelectionService(store,runner,evidence,JSON);
            assertThrows(BizException.class,()->service.select(body(1)));
            publish(db,bridge,bridge.prepare(rules()),"n04-v1");
            j.update("UPDATE compute_request SET requested_gpu_count=4 WHERE id=1");
            var existing=service.select(body(1));
            assertEquals("B",existing.selection().targetGroupCode());
            assertEquals(2,existing.selection().gpuCount()); assertEquals("JOB",existing.selection().specBasis());
            assertEquals("WAITING",existing.selection().selectionStatus());
            assertTrue(existing.output().path("blockingReasons").toString().contains("GROUP_RESERVED"));
            assertFalse(existing.selection().queuePersisted()); assertFalse(existing.selection().authorizesExecution());
            assertEquals(existing,service.detail(existing.selection().id()));
            long single=submit(store,1),large=submit(store,5);
            var observed=LocalDateTime.ofInstant(Instant.now().minusSeconds(1),ZoneOffset.UTC);
            j.update("UPDATE resource_observation SET observed_at=?,valid_until=? WHERE subject_kind='GPU' AND subject_id=8",observed,observed.plusMinutes(10));
            var flex=service.select(body(single));
            assertEquals("FLEX",flex.selection().targetGroupCode());
            assertEquals("TARGET_SELECTED",flex.selection().selectionStatus());
            assertEquals("REQUEST",flex.selection().specBasis()); assertNull(flex.selection().jobId());
            assertEquals(2,flex.evidenceIds().size());
            assertFalse(flex.evidenceSnapshot().snapshot().manifest().path("usable").asBoolean());
            j.update("UPDATE resource_observation SET source='UNTRUSTED' WHERE subject_kind='GPU' AND subject_id=8");
            var fallback=service.select(body(single));
            assertEquals("B",fallback.selection().targetGroupCode()); assertEquals("WAITING",fallback.selection().selectionStatus());
            assertTrue(fallback.evidenceIds().size()>2);
            assertEquals(flex,service.detail(flex.selection().id()));
            long three=submit(store,3);
            observed=LocalDateTime.ofInstant(Instant.now().minusSeconds(1),ZoneOffset.UTC);
            j.update("""
                    UPDATE resource_observation SET observed_at=?,valid_until=?,
                        values_json=JSON_OBJECT('healthy',IF(subject_id=4,CAST('false' AS JSON),CAST('true' AS JSON)),'occupied',CAST('true' AS JSON))
                    WHERE subject_kind='GPU' AND subject_id BETWEEN 1 AND 4
                    """,observed,observed.plusMinutes(10));
            var partial=service.select(body(three));
            assertEquals("A",partial.selection().targetGroupCode());
            assertEquals("PREEMPTION_CHECK_REQUIRED",partial.selection().selectionStatus());
            assertFalse(partial.output().path("blockingReasons").toString().contains("GPU_UNHEALTHY"));
            publish(db,bridge,bridge.prepare(rules()),"n04-v2");
            var noTarget=service.select(body(large));
            assertEquals("QUEUE_NO_TARGET",noTarget.selection().reasonCode()); assertNull(noTarget.selection().targetGroupId());
            assertEquals("WAITING",noTarget.selection().selectionStatus());
            assertNotEquals(existing.selection().ruleSetId(),noTarget.selection().ruleSetId());
            assertEquals(existing,service.detail(existing.selection().id()));
            assertEquals(5,service.list(1,20,null).getTotal());
            assertEquals(2,service.list(1,1,single).getTotal());
            assertEquals(fallback.selection().id(),service.list(1,1,single).getList().getFirst().id());
            assertThrows(BizException.class,()->service.list(1,20,0L));
            assertThrows(BizException.class,()->service.select(body(single).put("gpuCount",4)));
            assertThrows(BizException.class,()->service.select(body(single).put("asOf","2026-11-06T10:00:10Z")));
            assertThrows(BizException.class,()->service.select(body(9999)));
            assertThrows(BizException.class,()->service.select(body(single).put("nodeKey","missing")));
            assertThrows(BizException.class,()->service.detail(Long.MAX_VALUE));
            assertThrows(Exception.class,()->j.update("INSERT INTO target_group_selection_evidence(selection_id,snapshot_id,evidence_id) VALUES(?,?,?)",
                    flex.selection().id(),flex.selection().snapshotId(),fallback.evidenceIds().getFirst()));
            long selections=j.queryForObject("SELECT COUNT(*) FROM target_group_selection",Long.class);
            long snapshots=j.queryForObject("SELECT COUNT(*) FROM evidence_snapshot",Long.class);
            long values=j.queryForObject("SELECT COUNT(*) FROM evidence",Long.class);
            long refs=j.queryForObject("SELECT COUNT(*) FROM target_group_selection_evidence",Long.class);
            j.execute("CREATE TRIGGER fail_n04_ref BEFORE INSERT ON target_group_selection_evidence FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected target evidence failure'");
            assertThrows(Exception.class,()->service.select(body(single)));
            assertEquals(selections,j.queryForObject("SELECT COUNT(*) FROM target_group_selection",Long.class));
            assertEquals(snapshots,j.queryForObject("SELECT COUNT(*) FROM evidence_snapshot",Long.class));
            assertEquals(values,j.queryForObject("SELECT COUNT(*) FROM evidence",Long.class));
            assertEquals(refs,j.queryForObject("SELECT COUNT(*) FROM target_group_selection_evidence",Long.class));
            j.execute("DROP TRIGGER fail_n04_ref");
            assertEquals(0,j.queryForObject("SELECT COUNT(*) FROM compute_queue",Integer.class));
            assertEquals(0,j.queryForObject("SELECT COUNT(*) FROM decision",Integer.class));
            assertEquals(1,j.queryForObject("SELECT COUNT(*) FROM gpu_allocation",Integer.class));
            assertEquals("SUBMITTED",j.queryForObject("SELECT status FROM compute_request WHERE id=?",String.class,single));
            assertEquals(0,migration.migrate().migrationsExecuted);
        }
    }
}
