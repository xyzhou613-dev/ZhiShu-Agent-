package com.bemodel.compute;

import com.bemodel.common.BizException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import static com.bemodel.compute.ComputeReleaseBridgeTest.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="COMPUTE_ONTOLOGY_MYSQL_URL",matches="jdbc:mysql:.*")
class ComputeReservationDatabaseTest {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=ComputeRuleEvaluatorTest.JSON;
    private static final long GIB=1073741824L;
    private long submit(ComputeDatabase.Store store,String key,int count,long memory){
        var b=JSON.createObjectNode().put("employeeId",1).put("projectId",1).put("taskTypeId",1).put("clientRequestKey",key).put("requestedGpuCount",count);
        b.putObject("requestedResourceSpec").put("memoryBytes",memory).put("vramBytesPerGpu",80*GIB).put("gpuInterconnect","NONE");
        b.putObject("launchSpec").put("imageRef","demo/train");return new ComputeRequestService(store,JSON).submit(b).id();
    }
    private void fresh(ComputeMysqlTestDatabase db){var at=LocalDateTime.ofInstant(Instant.now().minusSeconds(1),ZoneOffset.UTC);db.business().update("UPDATE resource_observation SET observed_at=?,valid_until=?",at,at.plusMinutes(10));}
    private void releasePending(ComputeMysqlTestDatabase db,long allocation){
        db.business().update("UPDATE gpu_group SET pending_allocation_id=NULL WHERE pending_allocation_id=?",allocation);
        db.business().update("UPDATE gpu_allocation SET status='CANCELLED',closed_at=UTC_TIMESTAMP(6),close_reason='isolated test cleanup' WHERE id=? AND status='PENDING'",allocation);
    }
    private List<Object> race(Callable<?> a,Callable<?> b)throws Exception{
        try(var pool=Executors.newFixedThreadPool(2)){
            Callable<Object> left=()->{try{return a.call();}catch(BizException e){return e;}};
            Callable<Object> right=()->{try{return b.call();}catch(BizException e){return e;}};
            var first=pool.submit(left);var second=pool.submit(right);return List.of(first.get(),second.get());
        }
    }
    @Test void reservesExactlyOnceChecksHeadsAndSerializesSharedCapacityAndCancellation()throws Exception {
        try(var db=ComputeMysqlTestDatabase.create("10")){
            var migration=Flyway.configure().dataSource(db.businessUrl(),ComputeMysqlTestDatabase.username(),ComputeMysqlTestDatabase.password()).locations("classpath:db/a800-compute").target("11").load();
            assertEquals(1,migration.migrate().migrationsExecuted);
            var bridge=bridge(db);var j=db.business();
            // A test-only 120s published policy keeps a remote-DB concurrency test inside its lease.
            db.platform().update("UPDATE bm_compute_data_asset SET max_age_seconds=120,version=version+1 WHERE asset_code IN ('GPU_OBSERVATION','NODE_WATERMARK')");
            var definitions=rules();var r11=definitions.stream().filter(r->"R-11".equals(r.getRuleCode())).findFirst().orElseThrow();
            var def=(ObjectNode)JSON.readTree(r11.getExprJson());def.withObject("/parameters").put("maxAgeSeconds",120);r11.setExprJson(def.toString());r11.setVersion(r11.getVersion()+1);
            publish(db,bridge,bridge.prepare(definitions),"reserve-v1");
            var store=new ComputeDatabase.Store(j,null);var runner=new ComputeRuleRunner(bridge,new ComputeRuleService(null,JSON),JSON);
            var evidence=new ComputeEvidenceService(store,runner,JSON);var queue=ComputeQueueDatabaseTest.queue(store,runner);
            var projections=new ComputeCapacityProjectionService(store,runner,evidence,JSON);
            var service=new ComputeReservationService(store,queue,runner,projections,JSON,event->{});
            var legacy=service.reserve(ComputeQueueDatabaseTest.body(1));assertEquals("ALREADY_RESERVED",legacy.outcome());assertNull(legacy.receipt());
            long stale=submit(store,"stale",3,32*GIB);queue.enqueue(ComputeQueueDatabaseTest.body(stale));
            assertEquals("QUEUE_STALE_EVIDENCE",service.reserve(ComputeQueueDatabaseTest.body(stale)).reasonCode());
            assertEquals("CANCELLED",new ComputeRequestService(store,JSON).cancel(stale).status());
            ComputeEvidenceDatabaseTest.nodeObservation(db);fresh(db);
            long first=submit(store,"first",1,32*GIB),second=submit(store,"second",1,32*GIB);
            queue.enqueue(ComputeQueueDatabaseTest.body(first));queue.enqueue(ComputeQueueDatabaseTest.body(second));
            assertEquals("QUEUE_NOT_HEAD",service.reserve(ComputeQueueDatabaseTest.body(second)).reasonCode());
            fresh(db);var same=race(()->service.reserve(ComputeQueueDatabaseTest.body(first)),()->service.reserve(ComputeQueueDatabaseTest.body(first)));
            assertTrue(same.stream().allMatch(x->x instanceof ComputeReservationService.Outcome),same.toString());
            var outcomes=same.stream().map(x->(ComputeReservationService.Outcome)x).toList();
            assertEquals(1,outcomes.stream().filter(o->"RESERVED".equals(o.outcome())).count());
            assertEquals(outcomes.get(0).allocationId(),outcomes.get(1).allocationId());
            var receipt=outcomes.get(0).receipt();assertNotNull(receipt);assertEquals("PENDING",receipt.allocationStatus());
            assertEquals(receipt,service.detail(receipt.id()).receipt());
            assertEquals(1,j.queryForObject("SELECT COUNT(*) FROM compute_reservation_receipt WHERE request_id=?",Integer.class,first));
            assertEquals(2,j.queryForObject("SELECT COUNT(*) FROM decision WHERE request_id=?",Integer.class,first));
            assertEquals(0,j.queryForObject("SELECT COUNT(*) FROM hold_period WHERE allocation_id=?",Integer.class,receipt.allocationId()));
            assertEquals("QUEUE_CONFLICT",service.reserve(ComputeQueueDatabaseTest.body(second)).reasonCode());
            new ComputeRequestService(store,JSON).cancel(second);releasePending(db,receipt.allocationId());
            long cancelling=submit(store,"cancelling",1,32*GIB);fresh(db);queue.enqueue(ComputeQueueDatabaseTest.body(cancelling));
            fresh(db);var cancelled=race(()->service.reserve(ComputeQueueDatabaseTest.body(cancelling)),()->new ComputeRequestService(store,JSON).cancel(cancelling));
            String state=j.queryForObject("SELECT status FROM compute_request WHERE id=?",String.class,cancelling);
            if("APPROVED".equals(state)){
                long allocation=j.queryForObject("SELECT a.id FROM gpu_allocation a JOIN compute_job b ON b.id=a.job_id WHERE b.request_id=?",Long.class,cancelling);
                assertTrue(cancelled.stream().anyMatch(x->x instanceof BizException));releasePending(db,allocation);
            }else{
                assertEquals("CANCELLED",state);assertEquals(0,j.queryForObject("SELECT COUNT(*) FROM compute_job WHERE request_id=?",Integer.class,cancelling));
            }
            releasePending(db,1); // Free the fixture's B pending pointer; its deployment keeps holding B.
            long b=submit(store,"shared-b",2,80*GIB),a=submit(store,"shared-a",3,80*GIB);fresh(db);
            queue.enqueue(ComputeQueueDatabaseTest.body(b));queue.enqueue(ComputeQueueDatabaseTest.body(a));fresh(db);
            var shared=race(()->service.reserve(ComputeQueueDatabaseTest.body(a)),()->service.reserve(ComputeQueueDatabaseTest.body(b)));
            assertTrue(shared.stream().allMatch(x->x instanceof ComputeReservationService.Outcome),shared.toString());
            var sharedOutcomes=shared.stream().map(x->(ComputeReservationService.Outcome)x).toList();
            assertEquals(1,sharedOutcomes.stream().filter(o->"RESERVED".equals(o.outcome())).count(),shared.toString());
            assertTrue(sharedOutcomes.stream().anyMatch(o->"QUEUE_NO_CAPACITY".equals(o.reasonCode())),shared.toString());
            var winner=sharedOutcomes.stream().filter(o->"RESERVED".equals(o.outcome())).findFirst().orElseThrow().receipt();
            assertNotNull(winner.restoreRevisionId());
            assertEquals(3,j.queryForObject("SELECT COUNT(*) FROM hold_period WHERE ended_at IS NULL",Integer.class));
            long otherEvidence=j.queryForObject("SELECT id FROM evidence WHERE snapshot_id=? ORDER BY id LIMIT 1",Long.class,winner.snapshotId());
            long oldCheck=j.queryForObject("SELECT id FROM rule_check WHERE decision_id=? ORDER BY id LIMIT 1",Long.class,receipt.admissionDecisionId());
            assertThrows(Exception.class,()->j.update("INSERT INTO rule_check_evidence(rule_check_id,evidence_id) VALUES(?,?)",oldCheck,otherEvidence));
            long rollback=submit(store,"rollback",1,GIB);j.update("UPDATE compute_node SET memory_capacity_bytes=? WHERE id=1",512*GIB);fresh(db);
            queue.enqueue(ComputeQueueDatabaseTest.body(rollback));
            var tables=List.of("compute_job","active_job","gpu_allocation","allocation_gpu","decision","current_decision","rule_check","rule_check_evidence","compute_reservation_receipt","evidence_snapshot","capacity_projection_run");
            var counts=new java.util.HashMap<String,Long>();for(String table:tables)counts.put(table,j.queryForObject("SELECT COUNT(*) FROM "+table,Long.class));
            j.execute("CREATE TRIGGER fail_reserve BEFORE INSERT ON compute_reservation_receipt FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected receipt failure'");
            fresh(db);assertThrows(Exception.class,()->service.reserve(ComputeQueueDatabaseTest.body(rollback)));
            for(String table:tables)assertEquals(counts.get(table),j.queryForObject("SELECT COUNT(*) FROM "+table,Long.class),table);
            assertNull(j.queryForObject("SELECT pending_allocation_id FROM gpu_group WHERE group_code='FLEX'",Long.class));
            j.execute("DROP TRIGGER fail_reserve");
            publish(db,bridge,bridge.prepare(definitions),"reserve-v2");
            assertEquals(receipt.ruleSetId(),service.detail(receipt.id()).receipt().ruleSetId());
            assertFalse(service.list(1,20,first).getList().isEmpty());
            assertThrows(BizException.class,()->service.reserve(ComputeQueueDatabaseTest.body(rollback).put("pausedDeploymentId",1)));
            assertEquals(0,migration.migrate().migrationsExecuted);
        }
    }
}
