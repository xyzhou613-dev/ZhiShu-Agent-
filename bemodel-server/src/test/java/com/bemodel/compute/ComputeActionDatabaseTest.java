package com.bemodel.compute;

import com.bemodel.common.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named="COMPUTE_ONTOLOGY_MYSQL_URL",matches="jdbc:mysql:.*")
class ComputeActionDatabaseTest {
    ComputeMysqlTestDatabase db;
    HikariDataSource pool;
    JdbcTemplate j;
    ComputeDatabase.Store store;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    long workflow;

    @BeforeAll void initialize() throws Exception {
        db=ComputeMysqlTestDatabase.create("11");
        var config=new HikariConfig();config.setJdbcUrl(db.businessUrl());config.setUsername(ComputeMysqlTestDatabase.username());
        config.setPassword(ComputeMysqlTestDatabase.password());config.setMaximumPoolSize(6);pool=new HikariDataSource(config);
        j=new JdbcTemplate(pool);store=new ComputeDatabase.Store(j,new ComputeDatabase.Endpoint("test",3306,db.businessUrl()));
        long snapshot=ComputeReleaseBridge.insert(j,"INSERT INTO evidence_snapshot(as_of) VALUES(UTC_TIMESTAMP(6))");
        long decision=ComputeReleaseBridge.insert(j,"INSERT INTO decision(request_id,kind,outcome,as_of,snapshot_id) SELECT 1,'SUBMISSION','APPROVED',as_of,id FROM evidence_snapshot WHERE id=?",snapshot);
        workflow=ComputeReleaseBridge.insert(j,"INSERT INTO workflow_run(request_id,decision_id,group_id,job_id,restore_revision_id) VALUES(1,?,2,1,2)",decision);
        j.update("INSERT INTO execution_action(workflow_run_id,decision_id,action_type,target_kind,target_id,idempotency_key,status,receipt) VALUES(?,?,'STOP_JOB','JOB',1,'legacy','UNKNOWN',JSON_OBJECT('legacy',true))",workflow,decision);
        var migration=Flyway.configure().dataSource(pool).locations("classpath:db/a800-compute").target("12").load();
        assertEquals(1,migration.migrate().migrationsExecuted);
        assertEquals(0,migration.migrate().migrationsExecuted);
        assertTrue(j.queryForObject("SELECT receipt FROM execution_action WHERE idempotency_key='legacy'",String.class).contains("legacy"));
    }
    @AfterAll void cleanup() throws Exception {if(pool!=null)pool.close();if(db!=null)db.close();}
    ComputeActionService service(ComputeActionExecutor... adapters){return new ComputeActionService(store,json,List.of(adapters),30);}
    ComputeActionService.Intent intent(String key){return new ComputeActionService.Intent(workflow,"STOP_JOB","JOB",1,key,json.createObjectNode().put("reason","TEST").put("jobId",1L));}
    String key(){return "test:"+UUID.randomUUID();}
    ComputeActionService.Action prepared(ComputeActionService s){return s.prepare(intent(key()));}

    @Test void preparesIdempotentlyAndRefusesUnconfiguredOrMismatchedActions() throws Exception {
        var s=service();var i=intent(key());
        try(var threads=Executors.newFixedThreadPool(2)) {
            var first=threads.submit(()->s.prepare(i));var second=threads.submit(()->s.prepare(i));
            assertEquals(first.get(30,TimeUnit.SECONDS).id(),second.get(30,TimeUnit.SECONDS).id());
        }
        var a=s.prepare(i);assertEquals("PENDING",a.status());assertEquals(0,a.attemptCount());
        assertThrows(BizException.class,()->s.prepare(new ComputeActionService.Intent(workflow,"STOP_JOB","JOB",1,i.operationKey(),json.createObjectNode())));
        assertThrows(BizException.class,()->s.prepare(new ComputeActionService.Intent(workflow,"STOP","JOB",1,key(),json.createObjectNode())));
        assertThrows(BizException.class,()->s.prepare(new ComputeActionService.Intent(workflow,"STOP_JOB","JOB",999,key(),json.createObjectNode())));
        assertThrows(BizException.class,()->s.dispatch(a.id()));assertEquals("PENDING",s.get(a.id()).status());
        assertEquals(1,s.list(1,20,workflow,i.operationKey(),"PENDING").getTotal());
        assertThrows(BizException.class,()->s.list(1,20,null,null,"bogus"));
        assertThrows(Exception.class,()->j.update("UPDATE execution_action SET input_json='{}' WHERE id=?",a.id()));
        assertThrows(Exception.class,()->j.update("DELETE FROM resource_event WHERE action_id=?",a.id()));
        assertThrows(Exception.class,()->j.update("UPDATE resource_event SET payload='{}' WHERE action_id=?",a.id()));
        long legacy=j.queryForObject("SELECT id FROM execution_action WHERE idempotency_key='legacy'",Long.class);
        assertThrows(BizException.class,()->s.reconcile(legacy));
        assertThrows(BizException.class,()->store.transactions().execute(tx->s.prepare(intent(key()))));
    }

    @Test void sendsOnceWithoutHoldingTransactionsAndReplaysTerminalResults() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        ComputeActionExecutor adapter=new Adapter(){@Override public Reply execute(Command c,Duration t)throws Exception {
            calls.incrementAndGet();assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("RUNNING",j.queryForObject("SELECT status FROM execution_action WHERE id=?",String.class,c.actionId()));
            assertTrue(j.queryForObject("SELECT COUNT(*) FROM resource_event WHERE action_id=?",Integer.class,c.actionId())>=2);
            entered.countDown();assertTrue(release.await(20,TimeUnit.SECONDS));return ok();
        }};
        var s=service(adapter);var a=prepared(s);
        try(var threads=Executors.newFixedThreadPool(2)) {
            var first=threads.submit(()->s.dispatch(a.id()));assertTrue(entered.await(20,TimeUnit.SECONDS));
            try {assertEquals("RUNNING",s.dispatch(a.id()).status());assertEquals("RUNNING",s.reconcile(a.id()).status());}
            finally {release.countDown();}
            assertEquals("SUCCEEDED",first.get(30,TimeUnit.SECONDS).status());
        }
        assertEquals("SUCCEEDED",s.retry(a.id()).status());assertEquals(1,calls.get());assertEquals(1,s.get(a.id()).attemptCount());
        assertEquals(3,s.detail(a.id()).events().size());
        assertThrows(BizException.class,()->store.transactions().execute(tx->s.dispatch(a.id())));
    }

    @Test void timeoutQueriesOriginalKeyAfterRestartAndOnlyRetriesConfirmedSafeFailures() {
        var sends=new AtomicInteger();var queries=new AtomicInteger();var expected=new java.util.concurrent.atomic.AtomicReference<String>();
        ComputeActionExecutor adapter=new Adapter(){
            @Override public Reply execute(Command c,Duration t)throws Exception {expected.compareAndSet(null,c.operationKey());assertEquals(expected.get(),c.operationKey());if(sends.incrementAndGet()==1)throw new TimeoutException("private credentials must not persist");return ok();}
            @Override public Reply query(Command c,Duration t){queries.incrementAndGet();assertEquals(expected.get(),c.operationKey());return new Reply(State.FAILED,json.createObjectNode().put("confirmed",true),"SAFE_FAILURE",true);}
        };
        var s=service(adapter);var a=prepared(s);assertEquals("UNKNOWN",s.dispatch(a.id()).status());
        assertEquals("EXECUTOR_RESULT_UNKNOWN",s.get(a.id()).errorCode());assertFalse(s.detail(a.id()).events().toString().contains("credentials"));
        assertThrows(BizException.class,()->s.retry(a.id()));assertEquals(1,sends.get());
        var restarted=service(adapter);assertEquals("FAILED",restarted.reconcile(a.id()).status());
        assertTrue(restarted.get(a.id()).retryAllowed());assertEquals("SUCCEEDED",restarted.retry(a.id()).status());
        assertEquals(2,restarted.get(a.id()).attemptCount());assertEquals(1,restarted.get(a.id()).reconcileCount());assertEquals(1,queries.get());
        assertEquals(7,restarted.detail(a.id()).events().size());
    }

    @Test void expiredClaimsQueryInsteadOfResendingAndDiscardLateReplies() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var sends=new AtomicInteger();
        var adapter=new Adapter(){
            @Override public Reply execute(Command c,Duration t)throws Exception {sends.incrementAndGet();entered.countDown();assertTrue(release.await(20,TimeUnit.SECONDS));return new Reply(State.FAILED,json.createObjectNode(),"LATE_FAILURE",false);}
            @Override public Reply query(Command c,Duration t){return ok();}
        };
        var s=service(adapter);var a=prepared(s);
        try(var threads=Executors.newFixedThreadPool(2)) {
            var send=threads.submit(()->s.dispatch(a.id()));assertTrue(entered.await(20,TimeUnit.SECONDS));
            try {
                j.update("UPDATE execution_action SET lease_until='2020-01-01' WHERE id=?",a.id());
                assertThrows(BizException.class,()->s.dispatch(a.id()));
                assertEquals("SUCCEEDED",service(adapter).reconcile(a.id()).status());
            } finally {release.countDown();}
            assertEquals("SUCCEEDED",send.get(30,TimeUnit.SECONDS).status());
        }
        assertEquals(1,sends.get());assertEquals("ACTION_STALE_REPLY",s.detail(a.id()).events().getLast().get("event_type"));
    }

    @Test void receiptsAndEventsRollbackTogetherAndFailedPersistenceCanBeReconciled() {
        var calls=new AtomicInteger();var adapter=new Adapter(){@Override public Reply execute(Command c,Duration t){calls.incrementAndGet();return ok();}@Override public Reply query(Command c,Duration t){return ok();}};
        var s=service(adapter);var a=prepared(s);
        j.execute("CREATE TRIGGER test_fail_action_event BEFORE INSERT ON resource_event FOR EACH ROW BEGIN IF NEW.action_id="+a.id()+" AND NEW.event_type='ACTION_EXECUTION_RESULT' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test'; END IF; END");
        try {assertThrows(Exception.class,()->s.dispatch(a.id()));} finally {j.execute("DROP TRIGGER test_fail_action_event");}
        assertEquals("RUNNING",s.get(a.id()).status());assertNull(s.get(a.id()).receipt());assertEquals(2,s.detail(a.id()).events().size());assertEquals(1,calls.get());
        j.update("UPDATE execution_action SET lease_until='2020-01-01' WHERE id=?",a.id());
        assertEquals("SUCCEEDED",service(adapter).reconcile(a.id()).status());assertEquals(1,calls.get());
        var notSent=prepared(s);
        j.execute("CREATE TRIGGER test_fail_action_event BEFORE INSERT ON resource_event FOR EACH ROW BEGIN IF NEW.action_id="+notSent.id()+" AND NEW.event_type='ACTION_SEND_STARTED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test'; END IF; END");
        try {assertThrows(Exception.class,()->s.dispatch(notSent.id()));} finally {j.execute("DROP TRIGGER test_fail_action_event");}
        assertEquals("PENDING",s.get(notSent.id()).status());assertEquals(0,s.get(notSent.id()).attemptCount());assertEquals(1,calls.get());
    }

    @Test void rejectsAdapterChangesAndTreatsMalformedResponsesAsUnknown() {
        var bad=new Adapter(){@Override public Reply execute(Command c,Duration t){return null;}};
        var s=service(bad);var a=prepared(s);assertEquals("UNKNOWN",s.dispatch(a.id()).status());
        assertEquals("INVALID_EXECUTOR_RECEIPT",s.get(a.id()).errorCode());
        var other=new Adapter(){@Override public String key(){return "other";}};
        assertThrows(BizException.class,()->service(other).reconcile(a.id()));assertEquals("UNKNOWN",s.get(a.id()).status());
        var noRetry=new Adapter(){@Override public Reply execute(Command c,Duration t){return new Reply(State.FAILED,json.createObjectNode(),"FAILED",false);}};
        var failed=prepared(service(noRetry));assertEquals("FAILED",service(noRetry).dispatch(failed.id()).status());
        assertThrows(BizException.class,()->service(noRetry).retry(failed.id()));
    }

    @Test void changedWorkflowVersionPreventsSendingOldActions() {
        var calls=new AtomicInteger();var adapter=new Adapter(){@Override public Reply execute(Command c,Duration t){calls.incrementAndGet();return ok();}};
        var s=service(adapter);var a=prepared(s);
        j.update("UPDATE workflow_run SET row_version=row_version+1 WHERE id=?",workflow);
        assertThrows(BizException.class,()->s.dispatch(a.id()));assertEquals(0,calls.get());assertEquals("PENDING",s.get(a.id()).status());
    }

    class Adapter implements ComputeActionExecutor {
        public String key(){return "test-adapter";}
        public boolean supports(String type){return true;}
        public Reply execute(Command c,Duration t)throws Exception {return ok();}
        public Reply query(Command c,Duration t)throws Exception {return ok();}
        Reply ok(){return new Reply(State.SUCCEEDED,json.createObjectNode().put("confirmed",true),null,false);}
    }
}
