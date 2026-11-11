package com.bemodel.compute;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="COMPUTE_ONTOLOGY_MYSQL_URL",matches="jdbc:mysql:.*")
class ComputeBusinessTransactionsTest {
    private ComputeBusinessTransactions tx(ComputeMysqlTestDatabase db){return new ComputeBusinessTransactions(new ComputeDatabase.Store(db.business(),null));}
    private void job(JdbcTemplate j,long id) {
        j.update("INSERT INTO compute_request(id,employee_id,project_id,task_type_id,client_request_key,request_fingerprint,requested_gpu_count,requested_resource_spec,launch_spec,status) VALUES(?,1,1,1,?,SHA2(?,256),1,'{\"memoryBytes\":1024}','{\"imageRef\":\"test\"}','APPROVED')",id,"m07-"+id,"m07-"+id);
        j.update("INSERT INTO compute_job(id,request_id,task_type_id,effective_gpu_count,effective_resource_spec) VALUES(?,?,1,1,'{\"memoryBytes\":1024}')",id,id);
    }
    @Test void partialReservationAndPointerUpdateRollBackTogether() throws Exception {
        try(var db=ComputeMysqlTestDatabase.create()) {
            var j=db.business();job(j,2);var tx=tx(db);
            assertThrows(IllegalStateException.class,()->tx.writeLocked(1,4,jdbc->{
                long id=ComputeReleaseBridge.insert(jdbc,"INSERT INTO gpu_allocation(job_id,group_id,memory_bytes) VALUES(2,4,1024)");
                jdbc.update("UPDATE gpu_group SET pending_allocation_id=? WHERE id=4",id);
                throw new IllegalStateException("injected failure after writes");
            }));
            assertEquals(0,j.queryForObject("SELECT COUNT(*) FROM gpu_allocation WHERE job_id=2",Integer.class));
            assertNull(j.queryForObject("SELECT pending_allocation_id FROM gpu_group WHERE id=4",Long.class));
            assertEquals(1,j.queryForObject("SELECT COUNT(*) FROM gpu_allocation",Integer.class));
        }
    }
    private long handover(JdbcTemplate j,boolean fail) {
        j.update("UPDATE hold_period SET ended_at=UTC_TIMESTAMP(6) WHERE id=2");
        j.update("INSERT INTO allocation_gpu VALUES(1,5),(1,6)");
        j.update("UPDATE gpu_allocation SET status='OCCUPIED',activated_at=UTC_TIMESTAMP(6) WHERE id=1");
        long h=ComputeReleaseBridge.insert(j,"INSERT INTO hold_period(group_id,allocation_id,started_at) VALUES(2,1,UTC_TIMESTAMP(6))");
        j.update("UPDATE gpu_group SET current_hold_id=?,pending_allocation_id=NULL WHERE id=2",h);
        if(fail)throw new IllegalStateException("injected failure after handover");
        return h;
    }
    @Test void partialHandoverRestoresOriginalHoldAndSuccessfulHandoverHasOneOwner() throws Exception {
        try(var db=ComputeMysqlTestDatabase.create()) {
            var j=db.business();var tx=tx(db);
            assertThrows(IllegalStateException.class,()->tx.writeLocked(1,2,jdbc->handover(jdbc,true)));
            assertNull(j.queryForObject("SELECT ended_at FROM hold_period WHERE id=2",java.time.LocalDateTime.class));
            assertEquals("PENDING",j.queryForObject("SELECT status FROM gpu_allocation WHERE id=1",String.class));
            assertEquals(2L,j.queryForObject("SELECT current_hold_id FROM gpu_group WHERE id=2",Long.class));
            assertEquals(1L,j.queryForObject("SELECT pending_allocation_id FROM gpu_group WHERE id=2",Long.class));
            assertEquals(0,j.queryForObject("SELECT COUNT(*) FROM allocation_gpu",Integer.class));
            long hold=tx.writeLocked(1,2,jdbc->handover(jdbc,false));
            assertEquals(hold,j.queryForObject("SELECT current_hold_id FROM gpu_group WHERE id=2",Long.class));
            assertEquals(1,j.queryForObject("SELECT COUNT(*) FROM hold_period WHERE group_id=2 AND ended_at IS NULL",Integer.class));
            assertEquals(2,j.queryForObject("SELECT COUNT(*) FROM allocation_gpu WHERE allocation_id=1",Integer.class));
        }
    }
    @Test void concurrentRequestsSerializeOnNodeAndGroup() throws Exception {
        try(var db=ComputeMysqlTestDatabase.create()) {
            var j=db.business();job(j,2);job(j,3);var tx=tx(db);
            var start=new CountDownLatch(1);
            try(var workers=Executors.newFixedThreadPool(2)) {
                var tasks=new ArrayList<Future<Boolean>>();
                for(long id:List.of(2L,3L)) tasks.add(workers.submit(()->{start.await();return tx.writeLocked(1,4,jdbc->{
                    if(jdbc.queryForObject("SELECT COUNT(*) FROM gpu_allocation WHERE group_id=4 AND status='PENDING'",Integer.class)>0)return false;
                    long allocation=ComputeReleaseBridge.insert(jdbc,"INSERT INTO gpu_allocation(job_id,group_id,memory_bytes) VALUES(?,4,1024)",id);
                    jdbc.update("UPDATE gpu_group SET pending_allocation_id=? WHERE id=4",allocation);return true;
                });}));
                start.countDown();int winners=0;for(var result:tasks)if(result.get(20,TimeUnit.SECONDS))winners++;
                assertEquals(1,winners);assertEquals(1,j.queryForObject("SELECT COUNT(*) FROM gpu_allocation WHERE group_id=4 AND status='PENDING'",Integer.class));
            }
        }
    }
    @Test void platformTransactionCannotMasqueradeAsBusinessTransaction() throws Exception {
        try(var db=ComputeMysqlTestDatabase.create()) {
            var tx=tx(db);
            assertThrows(IllegalStateException.class,tx::requireBusinessTransaction);
            var platform=new TransactionTemplate(new DataSourceTransactionManager(db.platform().getDataSource()));
            platform.execute(status->{assertThrows(IllegalStateException.class,tx::requireBusinessTransaction);return null;});
            assertThrows(RuntimeException.class,()->tx.writeLocked(1,999,jdbc->{fail("wrong group must not execute writes");return null;}));
            assertThrows(IllegalArgumentException.class,()->new ComputeDatabase.Store(db.business(),null,new DataSourceTransactionManager(db.platform().getDataSource())));
        }
    }
}
