package com.bemodel.compute;
import com.bemodel.common.BizException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import static com.bemodel.compute.ComputeReleaseBridgeTest.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="COMPUTE_ONTOLOGY_MYSQL_URL",matches="jdbc:mysql:.*")
class ComputeQueueDatabaseTest {
    static final com.fasterxml.jackson.databind.ObjectMapper JSON=ComputeRuleEvaluatorTest.JSON;
    static ObjectNode body(long id){return JSON.createObjectNode().put("nodeKey","a800-demo").put("requestId",id);}
    static long submit(ComputeDatabase.Store store,String key,int count,long task){
        var b=JSON.createObjectNode().put("employeeId",1).put("projectId",1).put("taskTypeId",task).put("clientRequestKey",key).put("requestedGpuCount",count);
        b.putObject("requestedResourceSpec").put("memoryBytes",34359738368L).put("vramBytesPerGpu",85899345920L).put("gpuInterconnect","NONE");
        b.putObject("launchSpec").put("imageRef","demo/train");return new ComputeRequestService(store,JSON).submit(b).id();
    }
    static ComputeQueueService queue(ComputeDatabase.Store store,ComputeRuleRunner runner){
        var evidence=new ComputeEvidenceService(store,runner,JSON);
        return new ComputeQueueService(store,new ComputeTargetSelectionService(store,runner,evidence,JSON),JSON);
    }
    @Test void upgradesRanksReevaluatesCancelsAndSerializesDuplicateEnqueues()throws Exception {
        try(var db=ComputeMysqlTestDatabase.create("9")){
            var migration=Flyway.configure().dataSource(db.businessUrl(),ComputeMysqlTestDatabase.username(),ComputeMysqlTestDatabase.password()).locations("classpath:db/a800-compute").target("10").load();
            assertEquals(1,migration.migrate().migrationsExecuted);
            var bridge=bridge(db);publish(db,bridge,bridge.prepare(rules()),"queue-v1");
            var j=db.business();var store=new ComputeDatabase.Store(j,null);
            var runner=new ComputeRuleRunner(bridge,new ComputeRuleService(null,JSON),JSON);var queue=queue(store,runner);
            long first=submit(store,"queue-first",2,1),second=submit(store,"queue-second",2,1);
            j.update("UPDATE compute_request SET submitted_at='2026-11-06 10:00:00' WHERE id IN (?,?)",first,second);
            j.update("INSERT INTO task_type(id,type_code,name,priority,default_resource_spec) VALUES(2,'CUSTOM','custom',3,'{}')");
            long high=submit(store,"queue-high",2,2);
            queue.enqueue(body(second));queue.enqueue(body(first));queue.enqueue(body(high));
            assertTrue(queue.detail(high).head());assertEquals(2L,queue.detail(first).position());assertEquals(3L,queue.detail(second).position());
            assertEquals(3,queue.list("a800-demo","WAITING",1,20).getTotal());
            assertEquals("CANCELLED",new ComputeRequestService(store,JSON).cancel(high).status());
            assertEquals("CANCELLED",new ComputeRequestService(store,JSON).cancel(high).status());
            assertTrue(queue.detail(first).head());assertEquals("CANCELLED",queue.detail(high).status());
            try(var executor=Executors.newFixedThreadPool(2)){
                var a=executor.submit(()->queue.enqueue(body(first)));var b=executor.submit(()->queue.enqueue(body(first)));
                a.get();b.get();
            }
            assertEquals(1,j.queryForObject("SELECT COUNT(*) FROM compute_queue WHERE request_id=?",Integer.class,first));
            assertEquals(Instant.parse("2026-11-06T10:00:00Z"),queue.detail(first).submittedAt());
            long single=submit(store,"queue-single",1,1),large=submit(store,"queue-large",5,1);
            assertEquals("B",queue.enqueue(body(single)).targetGroupCode());
            assertNull(queue.enqueue(body(large)).position());
            var observed=LocalDateTime.ofInstant(Instant.now().minusSeconds(1),ZoneOffset.UTC);
            j.update("UPDATE resource_observation SET observed_at=?,valid_until=? WHERE subject_kind='GPU' AND subject_id=8",observed,observed.plusMinutes(10));
            queue.reevaluate("a800-demo");assertEquals("FLEX",queue.detail(single).targetGroupCode());assertTrue(queue.detail(single).head());
            j.update("UPDATE resource_observation SET status='ERROR',values_json=NULL WHERE subject_kind='GPU' AND subject_id=8");
            queue.reevaluate("a800-demo");assertEquals("B",queue.detail(single).targetGroupCode());assertFalse(queue.detail(single).head());
            assertThrows(BizException.class,()->queue.enqueue(body(first).put("priority",999)));
            assertThrows(BizException.class,()->queue.enqueue(body(1)));
            long failing=submit(store,"queue-rollback",2,1);
            long snapshots=j.queryForObject("SELECT COUNT(*) FROM evidence_snapshot",Long.class),targets=j.queryForObject("SELECT COUNT(*) FROM target_group_selection",Long.class);
            j.execute("CREATE TRIGGER fail_queue BEFORE INSERT ON compute_queue FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected queue failure'");
            assertThrows(Exception.class,()->queue.enqueue(body(failing)));
            assertEquals(snapshots,j.queryForObject("SELECT COUNT(*) FROM evidence_snapshot",Long.class));
            assertEquals(targets,j.queryForObject("SELECT COUNT(*) FROM target_group_selection",Long.class));
            j.execute("DROP TRIGGER fail_queue");
            assertEquals(1,j.queryForObject("SELECT COUNT(*) FROM gpu_allocation",Integer.class));
            assertEquals(0,migration.migrate().migrationsExecuted);
        }
    }
}
