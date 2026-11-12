package com.bemodel.compute;

import com.bemodel.common.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.bemodel.compute.ComputeActionExecutor.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named="COMPUTE_ONTOLOGY_MYSQL_URL",matches="jdbc:mysql:.*")
class ComputeSimulatorDatabaseTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final ComputeRuntimeSettings settings=new ComputeRuntimeSettings("SIMULATION","a800-demo","fixture",false,false);
    final MutableClock clock=new MutableClock();
    final ApplicationEventPublisher events=mock(ApplicationEventPublisher.class);
    ComputeMysqlTestDatabase db;HikariDataSource pool;JdbcTemplate j;ComputeDatabase.Store store;
    ComputeSimulatorService sim;ComputeSimulatorExecutor adapter;ComputeActionService actions;String baseline;long workflow;

    @BeforeAll void setup()throws Exception {
        db=ComputeMysqlTestDatabase.create("12");var c=new HikariConfig();c.setJdbcUrl(db.businessUrl());c.setUsername(ComputeMysqlTestDatabase.username());c.setPassword(ComputeMysqlTestDatabase.password());c.setMaximumPoolSize(6);pool=new HikariDataSource(c);
        j=new JdbcTemplate(pool);store=new ComputeDatabase.Store(j,new ComputeDatabase.Endpoint("test",3306,db.businessUrl()));
        var migration=Flyway.configure().dataSource(pool).locations("classpath:db/a800-compute").load();assertEquals(1,migration.migrate().migrationsExecuted);assertEquals(0,migration.migrate().migrationsExecuted);
        assertEquals(44,j.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history'",Integer.class));
        sim=new ComputeSimulatorService(store,json,settings,events,clock);adapter=new ComputeSimulatorExecutor(sim,settings);actions=new ComputeActionService(store,json,List.of(adapter),30);
        assertThrows(BizException.class,()->sim.state("a800-demo"));baseline=sim.initialize("a800-demo").toString();
        workflow=flow(1,1,2,2L);
    }
    long flow(long request,long job,long group,Long restore){long snapshot=ComputeReleaseBridge.insert(j,"INSERT INTO evidence_snapshot(as_of) VALUES(UTC_TIMESTAMP(6))");long decision=ComputeReleaseBridge.insert(j,"INSERT INTO decision(request_id,kind,outcome,as_of,snapshot_id) SELECT ?,'SUBMISSION','APPROVED',as_of,id FROM evidence_snapshot WHERE id=?",request,snapshot);return ComputeReleaseBridge.insert(j,"INSERT INTO workflow_run(request_id,decision_id,group_id,job_id,restore_revision_id) VALUES(?,?,?,?,?)",request,decision,group,job,restore);}
    @BeforeEach void fixture(){clock.offset.set(0);clearInvocations(events);j.update("UPDATE compute_simulator_state SET state_json=? WHERE node_id=1",baseline);}
    @AfterAll void cleanup()throws Exception{if(pool!=null)pool.close();if(db!=null)db.close();}
    String key(){return "sim:"+UUID.randomUUID();}
    ComputeActionService.Action prepare(String type){return prepare(type,key());}
    ComputeActionService.Action prepare(String type,String key){var input=json.createObjectNode();if("NOTIFY".equals(type))input.putArray("recipients").add("employee:DEMO-001").add("team:DEMO").add("team:DEMO");return actions.prepare(new ComputeActionService.Intent(workflow,type,"DEPLOYMENT",2,key,input));}
    ComputeActionService.Action run(String type){var a=prepare(type);return actions.dispatch(a.id());}
    Command command(ComputeActionService.Action a){return new Command(a.id(),a.workflowId(),a.decisionId(),a.operationKey(),a.actionType(),a.targetKind(),a.targetId(),a.input());}
    void scenario(String key,String mode,int delay){sim.scenario(json.createObjectNode().put("operationKey",key).put("mode",mode).put("delayMs",delay));}
    void stopped(){assertEquals("SUCCEEDED",run("DRAIN").status());assertEquals("SUCCEEDED",run("STOP").status());}

    @Test void duplicateAndConcurrentDeliveriesAreDurableAndDoNotDuplicateNotifications()throws Exception{
        var a=prepare("NOTIFY");assertEquals("SUCCEEDED",actions.dispatch(a.id()).status());var original=sim.operation(a.operationKey()).get("receipt");
        var restarted=new ComputeSimulatorService(store,json,settings,events,clock);var afterRestart=new ComputeSimulatorExecutor(restarted,settings);assertEquals(adapter.key(),afterRestart.key());
        try(var threads=Executors.newFixedThreadPool(2)){var first=threads.submit(()->adapter.execute(command(a),Duration.ofSeconds(30)));var second=threads.submit(()->afterRestart.execute(command(a),Duration.ofSeconds(30)));assertEquals(State.SUCCEEDED,first.get(30,TimeUnit.SECONDS).state());assertEquals(State.SUCCEEDED,second.get(30,TimeUnit.SECONDS).state());}
        var op=sim.operation(a.operationKey());assertEquals(1,((Number)op.get("effect_count")).intValue());assertEquals(3,((Number)op.get("send_count")).intValue());assertEquals(original,op.get("receipt"));assertEquals(2,((JsonNode)op.get("receipt")).path("recipients").size());
        assertEquals(1,j.queryForObject("SELECT COUNT(*) FROM resource_event WHERE source=? AND action_id=?",Integer.class,ComputeSimulatorService.SOURCE,a.id()));
        assertThrows(BizException.class,()->sim.scenario(json.createObjectNode().put("operationKey",a.operationKey()).put("mode","FAILURE")));
        assertThrows(BizException.class,()->adapter.execute(new Command(a.id(),a.workflowId(),a.decisionId(),a.operationKey(),a.actionType(),a.targetKind(),a.targetId(),json.createObjectNode()),Duration.ofSeconds(30)));
        assertThrows(Exception.class,()->j.update("DELETE FROM compute_simulator_operation WHERE action_id=?",a.id()));
    }
    @Test void delayedAcceptanceCompletesOnceOnQueryWithoutResending(){
        var a=prepare("NOTIFY");scenario(a.operationKey(),"SUCCESS",30_000);assertEquals("UNKNOWN",actions.dispatch(a.id()).status());
        assertEquals("UNKNOWN",actions.reconcile(a.id()).status());assertEquals(0,((Number)sim.operation(a.operationKey()).get("effect_count")).intValue());
        clock.offset.set(31_000);var restarted=new ComputeSimulatorExecutor(new ComputeSimulatorService(store,json,settings,events,clock),settings);var manager=new ComputeActionService(store,json,List.of(restarted),30);
        assertEquals("SUCCEEDED",manager.reconcile(a.id()).status());var op=sim.operation(a.operationKey());assertEquals(1,((Number)op.get("send_count")).intValue());assertEquals(1,((Number)op.get("effect_count")).intValue());assertEquals(2,manager.get(a.id()).reconcileCount());
    }
    @Test void lostReceiptRequiresQueryAndSafeRetryRequiresANewManagerAttempt(){
        var timeout=prepare("NOTIFY");scenario(timeout.operationKey(),"TIMEOUT",0);assertEquals("UNKNOWN",actions.dispatch(timeout.id()).status());assertThrows(BizException.class,()->actions.retry(timeout.id()));assertEquals(1,((Number)sim.operation(timeout.operationKey()).get("effect_count")).intValue());assertEquals("SUCCEEDED",actions.reconcile(timeout.id()).status());
        var failed=prepare("NOTIFY");scenario(failed.operationKey(),"FAILURE",0);assertEquals("FAILED",actions.dispatch(failed.id()).status());assertThrows(BizException.class,()->actions.retry(failed.id()));
        var retried=prepare("NOTIFY");scenario(retried.operationKey(),"FAIL_THEN_SUCCESS",0);assertEquals("FAILED",actions.dispatch(retried.id()).status());
        assertEquals(State.FAILED,adapter.execute(command(retried),Duration.ofSeconds(30)).state());assertEquals(1,((Number)sim.operation(retried.operationKey()).get("attempt_count")).intValue());
        assertEquals("SUCCEEDED",actions.retry(retried.id()).status());var op=sim.operation(retried.operationKey());assertEquals(2,((Number)op.get("attempt_count")).intValue());assertEquals(1,((Number)op.get("effect_count")).intValue());
    }
    @Test void stopRequiresDrainingAndZeroInflightButDoesNotChangeBusinessOwnership(){
        var busy=json.createObjectNode();busy.putArray("deployments").addObject().put("id",2).put("inFlightRequests",3);sim.configure("a800-demo",busy);
        assertEquals("SUCCEEDED",run("DRAIN").status());var stop=prepare("STOP");assertEquals("FAILED",actions.dispatch(stop.id()).status());assertTrue(sim.state("a800-demo").path("deployments").path("2").path("running").asBoolean());
        var patch=json.createObjectNode();patch.putArray("deployments").addObject().put("id",2).put("inFlightRequests",0);sim.configure("a800-demo",patch);
        assertEquals("SUCCEEDED",actions.retry(stop.id()).status());assertTrue(sim.state("a800-demo").path("gpus").path("5").path("owner").isNull());
        assertFalse(sim.initialize("a800-demo").path("deployments").path("2").path("running").asBoolean());
        assertEquals(2,j.queryForObject("SELECT current_hold_id FROM gpu_group WHERE id=2",Long.class));assertEquals("PENDING",j.queryForObject("SELECT status FROM gpu_allocation WHERE id=1",String.class));assertEquals(1,j.queryForObject("SELECT COUNT(*) FROM hold_period WHERE id=2 AND ended_at IS NULL",Integer.class));
    }
    @Test void restoresPinnedRevisionAndRequiresHealthBeforeResumingTraffic(){
        stopped();long newer=ComputeReleaseBridge.insert(j,"INSERT INTO deployment_revision(deployment_id,version,image_ref,command_args,mounts,health_check,memory_bytes) VALUES(2,2,'test/new','[]','[]','{}',1024)");j.update("UPDATE deployment SET current_revision_id=? WHERE id=2",newer);
        var restored=run("RESTORE");assertEquals("SUCCEEDED",restored.status());assertEquals(2,restored.receipt().path("restoreRevisionId").asLong());assertFalse(sim.state("a800-demo").path("deployments").path("2").path("acceptingTraffic").asBoolean());
        var patch=json.createObjectNode();patch.putArray("gpus").addObject().put("id",5).put("healthy",false);sim.configure("a800-demo",patch);
        assertEquals("FAILED",run("HEALTH_CHECK").status());var resume=prepare("RESUME_TRAFFIC");assertEquals("FAILED",actions.dispatch(resume.id()).status());
        patch.withArray("gpus").removeAll().addObject().put("id",5).put("healthy",true);sim.configure("a800-demo",patch);assertEquals("SUCCEEDED",actions.retry(resume.id()).status());assertTrue(sim.state("a800-demo").path("deployments").path("2").path("acceptingTraffic").asBoolean());
    }
    @Test void jobCannotStartBeforeActualHandoverAndStoppingOnlyReleasesSimulatedGpu(){
        String key=key();j.update("INSERT INTO compute_request(employee_id,project_id,task_type_id,client_request_key,request_fingerprint,requested_gpu_count,requested_resource_spec,launch_spec) VALUES(1,1,1,?,?,1,'{}','{}')",key,"a".repeat(64));long request=j.queryForObject("SELECT id FROM compute_request WHERE client_request_key=?",Long.class,key);
        long job=ComputeReleaseBridge.insert(j,"INSERT INTO compute_job(request_id,task_type_id,effective_gpu_count,effective_resource_spec) VALUES(?,1,1,'{}')",request);long allocation=ComputeReleaseBridge.insert(j,"INSERT INTO gpu_allocation(job_id,group_id,memory_bytes) VALUES(?,4,1024)",job);long flow=flow(request,job,4,null);
        var a=actions.prepare(new ComputeActionService.Intent(flow,"START_JOB","JOB",job,key(),json.createObjectNode()));assertEquals("FAILED",actions.dispatch(a.id()).status());assertEquals("SIMULATED_HANDOVER_REQUIRED",actions.get(a.id()).errorCode());
        j.update("INSERT INTO allocation_gpu VALUES(?,8)",allocation);j.update("UPDATE gpu_allocation SET status='OCCUPIED',activated_at=UTC_TIMESTAMP(6) WHERE id=?",allocation);
        assertEquals("FAILED",actions.retry(a.id()).status());assertEquals("SIMULATED_HANDOVER_REQUIRED",actions.get(a.id()).errorCode());
        long hold=ComputeReleaseBridge.insert(j,"INSERT INTO hold_period(group_id,allocation_id,started_at) VALUES(4,?,UTC_TIMESTAMP(6))",allocation);j.update("UPDATE gpu_group SET current_hold_id=? WHERE id=4",hold);
        assertEquals("SUCCEEDED",actions.retry(a.id()).status());assertEquals("JOB:"+job,sim.state("a800-demo").path("gpus").path("8").path("owner").asText());
        var stop=actions.prepare(new ComputeActionService.Intent(flow,"STOP_JOB","JOB",job,key(),json.createObjectNode()));assertEquals("SUCCEEDED",actions.dispatch(stop.id()).status());assertTrue(sim.state("a800-demo").path("gpus").path("8").path("owner").isNull());assertEquals("OCCUPIED",j.queryForObject("SELECT status FROM gpu_allocation WHERE id=?",String.class,allocation));
    }
    @Test void observationFailuresAreExplicitAndBatchFailureRollsBackWithoutPublishingEvents(){
        var result=sim.observe("a800-demo",json.createObjectNode());assertEquals(9,((List<?>)result.get("observationIds")).size());verify(events).publishEvent(any(ComputeQueueScheduler.ResourceChanged.class));
        var body=json.createObjectNode().put("failNode",true);body.putArray("failGpuIds").add(5);sim.observe("a800-demo",body);
        assertEquals(2,j.queryForObject("SELECT COUNT(*) FROM resource_observation WHERE source=? AND status='ERROR'",Integer.class,ComputeSimulatorService.SOURCE));
        assertEquals(0,j.queryForObject("SELECT COUNT(*) FROM resource_observation WHERE source=? AND status='ERROR' AND values_json IS NOT NULL",Integer.class,ComputeSimulatorService.SOURCE));
        long before=j.queryForObject("SELECT COUNT(*) FROM resource_observation",Long.class);clearInvocations(events);
        j.execute("CREATE TRIGGER test_sim_observation_failure BEFORE INSERT ON resource_observation FOR EACH ROW BEGIN IF NEW.source='A800_SIMULATOR_V1' AND NEW.subject_id=5 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test'; END IF; END");
        try{assertThrows(Exception.class,()->sim.observe("a800-demo",json.createObjectNode()));}finally{j.execute("DROP TRIGGER test_sim_observation_failure");}
        assertEquals(before,j.queryForObject("SELECT COUNT(*) FROM resource_observation",Long.class));verifyNoInteractions(events);
        assertThrows(BizException.class,()->sim.observe("a800-demo",json.createObjectNode().put("validForSeconds",0)));assertThrows(BizException.class,()->sim.initialize("other-node"));
        assertThrows(BizException.class,()->sim.scenario(json.createObjectNode().put("operationKey",123).put("mode","SUCCESS")));
    }
    @Test void failedEffectPersistenceRollsBackAndCanBeSafelyReconciledAsNotAccepted(){
        var a=prepare("NOTIFY");j.execute("CREATE TRIGGER test_sim_result_failure BEFORE INSERT ON resource_event FOR EACH ROW BEGIN IF NEW.source='A800_SIMULATOR_V1' AND NEW.action_id="+a.id()+" THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test'; END IF; END");
        try{assertEquals("UNKNOWN",actions.dispatch(a.id()).status());}finally{j.execute("DROP TRIGGER test_sim_result_failure");}
        assertThrows(BizException.class,()->sim.operation(a.operationKey()));assertEquals("FAILED",actions.reconcile(a.id()).status());assertTrue(actions.get(a.id()).retryAllowed());assertEquals("SUCCEEDED",actions.retry(a.id()).status());assertEquals(1,((Number)sim.operation(a.operationKey()).get("effect_count")).intValue());
    }
    @Test void capacityBlocksStartingButCannotPreventNotificationOrStopping(){
        long capacity=j.queryForObject("SELECT memory_capacity_bytes FROM compute_node WHERE id=1",Long.class);
        j.update("UPDATE compute_node SET memory_capacity_bytes=memory_reserved_bytes+1024 WHERE id=1");
        try{
            assertEquals("SUCCEEDED",run("NOTIFY").status());stopped();var restore=run("RESTORE");assertEquals("FAILED",restore.status());assertEquals("SIMULATED_CAPACITY_EXCEEDED",restore.errorCode());
            assertFalse(restore.receipt().has("running"));assertTrue(sim.state("a800-demo").path("gpus").path("5").path("owner").isNull());
        }finally{j.update("UPDATE compute_node SET memory_capacity_bytes=? WHERE id=1",capacity);}
    }
    @Test void sourceMustBeExplicitlyRegisteredAndPublishedBeforeSnapshotsTrustIt(){
        var bridge=ComputeReleaseBridgeTest.bridge(db);ComputeReleaseBridgeTest.publish(db,bridge,bridge.prepare(ComputeReleaseBridgeTest.rules()),"sim-old-source");
        var runner=new ComputeRuleRunner(bridge,new ComputeRuleService(null,json),json);var evidence=new ComputeEvidenceService(store,runner,json);
        sim.observe("a800-demo",json.createObjectNode());var request=json.createObjectNode().put("nodeKey","a800-demo").put("requestId",1);
        assertTrue(evidence.capture(request).snapshot().manifest().path("issues").toString().contains("SOURCE_MISMATCH"));
        db.platform().update("UPDATE bm_compute_data_asset SET authoritative_source=?,source_kind='SIMULATED',max_age_seconds=30,version=version+1 WHERE asset_code IN ('GPU_OBSERVATION','NODE_WATERMARK')",ComputeSimulatorService.SOURCE);
        ComputeReleaseBridgeTest.publish(db,bridge,bridge.prepare(ComputeReleaseBridgeTest.rules()),"sim-new-source");sim.observe("a800-demo",json.createObjectNode());
        var captured=evidence.capture(request);assertTrue(captured.snapshot().manifest().path("usable").asBoolean(),captured.snapshot().manifest().toString());assertEquals(10,captured.evidence().size());
    }
    static class MutableClock extends Clock {final AtomicLong offset=new AtomicLong();public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return Instant.now().plusMillis(offset.get());}}
}
