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
class ComputeCapacityProjectionDatabaseTest {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=ComputeRuleEvaluatorTest.JSON;
    private ObjectNode body(){return JSON.createObjectNode().put("nodeKey","a800-demo").put("requestId",1).put("mode","PLAN");}
    @Test void upgradesProjectsBothModesAndAtomicallyPreservesFactsVersionsAndEvidence()throws Exception {
        try(var db=ComputeMysqlTestDatabase.create("8")) {
            var migration=Flyway.configure().dataSource(db.businessUrl(),ComputeMysqlTestDatabase.username(),ComputeMysqlTestDatabase.password()).locations("classpath:db/a800-compute").load();
            assertEquals(1,migration.migrate().migrationsExecuted);
            var bridge=bridge(db);var j=db.business();var store=new ComputeDatabase.Store(j,null);
            var runner=new ComputeRuleRunner(bridge,new ComputeRuleService(null,JSON),JSON);
            var service=new ComputeCapacityProjectionService(store,runner,new ComputeEvidenceService(store,runner,JSON),JSON);
            assertThrows(BizException.class,()->service.project(body()));
            publish(db,bridge,bridge.prepare(rules()),"n05-v1");
            var original=service.project(body().put("pausedDeploymentId",2));
            assertEquals(77309411328L,original.output().path("projectedBytes").path("memoryBytes").asLong());
            assertEquals("UNKNOWN",original.run().feasibility());
            assertEquals(original,service.detail(original.run().id()));
            var request=JSON.createObjectNode().put("employeeId",1).put("projectId",1).put("taskTypeId",1)
                    .put("requestedGpuCount",3).put("clientRequestKey","n05-plan");
            request.putObject("requestedResourceSpec").put("memoryBytes",34359738368L).put("vramBytesPerGpu",85899345920L).put("gpuInterconnect","NONE");
            request.putObject("launchSpec").put("imageRef","demo/train");
            long requestId=new ComputeRequestService(store,JSON).submit(request).id();
            ComputeEvidenceDatabaseTest.nodeObservation(db);
            var observed=LocalDateTime.ofInstant(Instant.now().minusSeconds(1),ZoneOffset.UTC);
            j.update("UPDATE resource_observation SET observed_at=?,valid_until=?",observed,observed.plusMinutes(10));
            var plan=service.project(body().put("requestId",requestId).put("pausedDeploymentId",1));
            assertEquals("FEASIBLE",plan.run().feasibility(),plan.toString());
            assertEquals(94489280512L,plan.output().path("projectedBytes").path("memoryBytes").asLong());
            assertEquals(3,plan.output().path("usedGpuIds").size());
            var restore=service.project(body().put("mode","RESTORE").put("restoreRevisionId",2));
            assertEquals(94489280512L,restore.output().path("projectedBytes").path("memoryBytes").asLong());
            assertTrue(restore.checks().stream().anyMatch(c->"R-15".equals(c.ruleCode())&&"PASS".equals(c.result())));
            assertEquals("UNKNOWN",restore.run().feasibility());
            publish(db,bridge,bridge.prepare(rules()),"n05-v2");
            var newer=service.project(body());assertNotEquals(original.run().ruleSetId(),newer.run().ruleSetId());
            assertEquals(original,service.detail(original.run().id()));
            assertEquals(4,service.list(1,20,null).getTotal());assertEquals(1,service.list(1,20,requestId).getTotal());
            assertThrows(BizException.class,()->service.project(body().put("mode","BAD")));
            assertThrows(BizException.class,()->service.project(body().put("usedGpuIds",1)));
            assertThrows(BizException.class,()->service.project(body().put("asOf","2026-11-06T10:00:10Z")));
            assertThrows(BizException.class,()->service.project(body().put("mode","RESTORE")));
            assertThrows(BizException.class,()->service.project(body().put("pausedDeploymentId",3)));
            assertThrows(BizException.class,()->service.detail(Long.MAX_VALUE));
            assertThrows(Exception.class,()->j.update("INSERT INTO capacity_projection_evidence(run_id,snapshot_id,evidence_id) VALUES(?,?,?)",original.run().id(),original.run().snapshotId(),newer.evidenceIds().getFirst()));
            long runs=j.queryForObject("SELECT COUNT(*) FROM capacity_projection_run",Long.class),checks=j.queryForObject("SELECT COUNT(*) FROM capacity_projection_check",Long.class),
                    snapshots=j.queryForObject("SELECT COUNT(*) FROM evidence_snapshot",Long.class),values=j.queryForObject("SELECT COUNT(*) FROM evidence",Long.class);
            j.execute("CREATE TRIGGER fail_n05_write BEFORE INSERT ON capacity_projection_evidence FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected projection evidence failure'");
            assertThrows(Exception.class,()->service.project(body()));
            assertEquals(runs,j.queryForObject("SELECT COUNT(*) FROM capacity_projection_run",Long.class));
            assertEquals(checks,j.queryForObject("SELECT COUNT(*) FROM capacity_projection_check",Long.class));
            assertEquals(snapshots,j.queryForObject("SELECT COUNT(*) FROM evidence_snapshot",Long.class));
            assertEquals(values,j.queryForObject("SELECT COUNT(*) FROM evidence",Long.class));
            j.execute("DROP TRIGGER fail_n05_write");
            assertEquals(0,j.queryForObject("SELECT COUNT(*) FROM compute_queue",Integer.class));
            assertEquals(1,j.queryForObject("SELECT COUNT(*) FROM gpu_allocation",Integer.class));
            assertEquals(3,j.queryForObject("SELECT COUNT(*) FROM hold_period WHERE ended_at IS NULL",Integer.class));
            assertEquals("SUBMITTED",j.queryForObject("SELECT status FROM compute_request WHERE id=?",String.class,requestId));
            assertEquals(0,migration.migrate().migrationsExecuted);
        }
    }
}
