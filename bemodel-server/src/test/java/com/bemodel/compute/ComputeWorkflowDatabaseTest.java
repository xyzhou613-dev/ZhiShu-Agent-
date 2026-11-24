package com.bemodel.compute;
import com.fasterxml.jackson.databind.*;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named="COMPUTE_ONTOLOGY_MYSQL_URL",matches="jdbc:mysql:.*")
class ComputeWorkflowDatabaseTest {
    ComputeMysqlTestDatabase db;HikariDataSource pool;JdbcTemplate j;ComputeDatabase.Store store;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();ComputeSimulatorService sim;ComputeWorkflowService wf;ComputeRequestService requests;ComputeQueueService queue;ComputeReservationService reservations;ComputeCapacityProjectionService projection;ComputeActionService actions;
    @BeforeAll void setup()throws Exception{
        db=ComputeMysqlTestDatabase.create();var c=new HikariConfig();c.setJdbcUrl(db.businessUrl());c.setUsername(ComputeMysqlTestDatabase.username());c.setPassword(ComputeMysqlTestDatabase.password());c.setMaximumPoolSize(6);pool=new HikariDataSource(c);j=new JdbcTemplate(pool);store=new ComputeDatabase.Store(j,null);
        var bridge=ComputeReleaseBridgeTest.bridge(db);db.platform().update("UPDATE bm_compute_data_asset SET authoritative_source='A800_SIMULATOR_V1',source_kind='SIMULATED',max_age_seconds=120,version=version+1 WHERE asset_code IN ('GPU_OBSERVATION','NODE_WATERMARK')");ComputeReleaseBridgeTest.publish(db,bridge,bridge.prepare(ComputeReleaseBridgeTest.rules()),"workflow-v1");
        var events=mock(org.springframework.context.ApplicationEventPublisher.class);var runtime=new ComputeRuntimeSettings("SIMULATION","a800-demo","test",false,false);sim=new ComputeSimulatorService(store,json,runtime,events);sim.initialize("a800-demo");
        var runner=new ComputeRuleRunner(bridge,new ComputeRuleService(null,json),json);var evidence=new ComputeEvidenceService(store,runner,json);projection=new ComputeCapacityProjectionService(store,runner,evidence,json);queue=new ComputeQueueService(store,new ComputeTargetSelectionService(store,runner,evidence,json),json);reservations=new ComputeReservationService(store,queue,runner,projection,json,events);requests=new ComputeRequestService(store,json);actions=new ComputeActionService(store,json,List.of(new ComputeSimulatorExecutor(sim,runtime)),30);wf=new ComputeWorkflowService(store,actions,projection,json);
    }
    @AfterAll void cleanup()throws Exception{if(pool!=null)pool.close();if(db!=null)db.close();}
    long create(){sim.observe("a800-demo",json.createObjectNode());var body=json.createObjectNode().put("employeeId",1).put("projectId",1).put("taskTypeId",1).put("clientRequestKey",UUID.randomUUID().toString()).put("requestedGpuCount",1);body.putObject("requestedResourceSpec").put("memoryBytes",1024).put("vramBytesPerGpu",80L*1024*1024*1024).put("gpuInterconnect","NONE");body.putObject("launchSpec").put("imageRef","demo/test");long request=requests.submit(body).id();var spec=json.createObjectNode().put("nodeKey","a800-demo").put("requestId",request);queue.enqueue(spec);var reserved=reservations.reserve(spec);assertEquals("RESERVED",reserved.outcome(),reserved.toString());long id=((Number)wf.create(reserved.receipt().id()).get("id")).longValue();assertEquals(id,((Number)wf.create(reserved.receipt().id()).get("id")).longValue());return id;}
    String step(long id){return wf.detail(id).get("step").toString();}
    String allocation(long id){return j.queryForObject("SELECT a.status FROM gpu_allocation a JOIN workflow_run w ON w.job_id=a.job_id WHERE w.id=?",String.class,id);}
    void fresh(){sim.observe("a800-demo",json.createObjectNode());}
    void close(long id){wf.stop(id,true);for(int i=0;i<7&&!"DONE".equals(step(id));i++)wf.advance(id);assertEquals("DONE",step(id),wf.detail(id).toString());}
    @Test void concurrentHandoverLostStopAndRestartKeepExactlyOneHold()throws Exception{
        long id=create();fresh();try(var threads=Executors.newFixedThreadPool(2)){var a=threads.submit(()->wf.advance(id));var b=threads.submit(()->wf.advance(id));a.get();b.get();}
        assertEquals("START_JOB",step(id));assertEquals("OCCUPIED",allocation(id));wf.advance(id);assertEquals("RUNNING",step(id));
        wf.stop(id,false);wf.advance(id);long version=((Number)wf.detail(id).get("row_version")).longValue();sim.scenario(json.createObjectNode().put("operationKey","workflow:"+id+":STOP_JOB:"+version).put("mode","TIMEOUT"));wf.advance(id);assertEquals("STOP_JOB",step(id));assertEquals("OCCUPIED",allocation(id));
        var restarted=new ComputeWorkflowService(store,actions,projection,json);restarted.advance(id);assertEquals("RELEASE",step(id));restarted.advance(id);assertEquals("DONE",step(id));assertEquals("RELEASED",allocation(id));restarted.advance(id);
        assertEquals(1,j.queryForObject("SELECT COUNT(*) FROM hold_period h JOIN workflow_run w ON w.job_id=(SELECT job_id FROM gpu_allocation WHERE id=h.allocation_id) WHERE w.id=?",Integer.class,id));
    }
    @Test void cancelBeforeHandoverDoesNotCreateAHold(){long id=create();close(id);assertEquals("CANCELLED",allocation(id));}
    @Test void failedStartStillRequiresStopBeforeRelease(){long id=create();fresh();wf.advance(id);sim.scenario(json.createObjectNode().put("operationKey","workflow:"+id+":START_JOB:1").put("mode","FAILURE"));wf.advance(id);assertEquals("STOP_JOB",step(id));assertEquals("OCCUPIED",allocation(id));wf.advance(id);assertEquals("RELEASE",step(id));wf.advance(id);assertEquals("RELEASED",allocation(id));assertEquals("START_FAILED",wf.detail(id).get("end_reason"));assertEquals("FAILED",j.queryForObject("SELECT b.status FROM compute_job b JOIN workflow_run w ON w.job_id=b.id WHERE w.id=?",String.class,id));}
    @Test void cancelDuringUnknownStartMustResolveItFirst(){long id=create();fresh();wf.advance(id);sim.scenario(json.createObjectNode().put("operationKey","workflow:"+id+":START_JOB:1").put("mode","TIMEOUT"));wf.advance(id);assertEquals("START_JOB",step(id));wf.stop(id,true);assertEquals("OCCUPIED",allocation(id));wf=new ComputeWorkflowService(store,actions,projection,json);close(id);assertEquals("RELEASED",allocation(id));}
    @Test void handoverFailureRollsBackPointersAndExpiredLeaseCanResume(){long id=create();fresh();j.execute("CREATE TRIGGER test_workflow_failure BEFORE INSERT ON resource_event FOR EACH ROW BEGIN IF NEW.event_type='WORKFLOW_HANDOVER' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test'; END IF; END");try{wf.advance(id);assertEquals("PENDING",allocation(id));assertEquals("HANDOVER",step(id));assertNull(j.queryForObject("SELECT current_hold_id FROM gpu_group WHERE group_code='FLEX'",Long.class));}finally{j.execute("DROP TRIGGER test_workflow_failure");}
        j.update("UPDATE workflow_run SET lease_token='crashed',lease_until='2020-01-01' WHERE id=?",id);fresh();wf.advance(id);assertEquals("START_JOB",step(id));close(id);
    }
}
