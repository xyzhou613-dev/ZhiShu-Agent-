package com.bemodel.compute;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="COMPUTE_ONTOLOGY_MYSQL_URL",matches="jdbc:mysql:.*")
class ComputeNormDatabaseTest {
    @Test void protectsHistoryEvenWhenBypassingTheApplication() throws Exception {
        try(var db=ComputeMysqlTestDatabase.create()) {
            var j=db.business();
            assertThrows(Exception.class,()->j.update("UPDATE hold_period SET started_at=DATE_ADD(started_at,INTERVAL 1 SECOND) WHERE id=2"));
            assertThrows(Exception.class,()->j.update("UPDATE hold_period SET deployment_id=1 WHERE id=2"));
            j.update("UPDATE hold_period SET ended_at='2026-11-11 00:00:00' WHERE id=2");
            assertThrows(Exception.class,()->j.update("UPDATE hold_period SET ended_at='2026-11-11 00:00:01' WHERE id=2"));
            assertThrows(Exception.class,()->j.update("UPDATE hold_period SET ended_at=NULL WHERE id=2"));
            assertThrows(Exception.class,()->j.update("DELETE FROM hold_period WHERE id=2"));
            assertThrows(Exception.class,()->j.update("INSERT INTO hold_period(group_id,deployment_id,deployment_revision_id,started_at,ended_at) VALUES(2,2,2,'2026-11-07','2026-11-08')"));
            assertEquals(1,j.update("INSERT INTO hold_period(group_id,deployment_id,deployment_revision_id,started_at) VALUES(2,2,2,'2026-11-11 00:00:00')"));
        }
    }
    @Test void activationRequiresExactCardsAndOnlyOccupiedAllocationMayHold() throws Exception {
        try(var db=ComputeMysqlTestDatabase.create()) {
            var j=db.business();
            assertThrows(Exception.class,()->j.update("INSERT INTO hold_period(group_id,allocation_id,started_at) VALUES(2,1,'2026-11-11')"));
            assertThrows(Exception.class,()->j.update("UPDATE gpu_allocation SET status='OCCUPIED' WHERE id=1"));
            assertThrows(Exception.class,()->j.update("INSERT INTO allocation_gpu VALUES(1,7)"));
            j.update("INSERT INTO allocation_gpu VALUES(1,5),(1,6)");
            assertEquals(1,j.update("UPDATE gpu_allocation SET status='OCCUPIED',activated_at=UTC_TIMESTAMP(6) WHERE id=1"));
            assertThrows(Exception.class,()->j.update("DELETE FROM allocation_gpu WHERE allocation_id=1"));
            assertThrows(Exception.class,()->j.update("UPDATE allocation_gpu SET gpu_id=8 WHERE allocation_id=1 AND gpu_id=5"));
            j.update("UPDATE hold_period SET ended_at='2026-11-11' WHERE id=2");
            assertEquals(1,j.update("INSERT INTO hold_period(group_id,allocation_id,started_at) VALUES(2,1,'2026-11-11')"));
        }
    }
    @Test void existingUniquenessAndTaskIdentitySurviveDirectSql() throws Exception {
        try(var db=ComputeMysqlTestDatabase.create()) {
            var j=db.business();
            assertThrows(Exception.class,()->j.update("INSERT INTO active_job(request_id,job_id) VALUES(1,1)"));
            assertThrows(Exception.class,()->j.update("INSERT INTO gpu_allocation(job_id,group_id,memory_bytes) VALUES(1,1,0)"));
            assertThrows(Exception.class,()->j.update("INSERT INTO hold_period(group_id,deployment_id,deployment_revision_id,started_at) VALUES(1,1,1,'2026-11-11')"));
            assertThrows(Exception.class,()->j.update("INSERT INTO deployment(group_id,project_id,owner_employee_id,name) VALUES(1,1,1,'duplicate')"));
            j.update("INSERT INTO task_type(id,type_code,name,priority,default_resource_spec) VALUES(2,'OTHER','other',1,'{}')");
            assertThrows(Exception.class,()->j.update("INSERT INTO compute_job(request_id,task_type_id,effective_gpu_count,effective_resource_spec) VALUES(1,2,1,'{}')"));
            assertThrows(Exception.class,()->j.update("UPDATE compute_job SET effective_gpu_count=1 WHERE id=1"));
        }
    }
    @Test void registersContractsWithoutPublishingOrExecuting() throws Exception {
        try(var db=ComputeMysqlTestDatabase.create()) {
            var f=Flyway.configure().dataSource(db.platformUrl(),ComputeMysqlTestDatabase.username(),ComputeMysqlTestDatabase.password()).locations("classpath:db/compute-platform").load();
            f.migrate(); var j=db.platform();
            assertEquals(10,j.queryForObject("SELECT COUNT(*) FROM bm_action WHERE contract_json IS NOT NULL",Integer.class));
            assertEquals(26,j.queryForObject("SELECT COUNT(*) FROM bm_axiom WHERE enforcement_json IS NOT NULL",Integer.class));
            assertEquals(0,j.queryForObject("SELECT COUNT(*) FROM bm_action WHERE status<>'DRAFT'",Integer.class));
            assertEquals(0,db.business().queryForObject("SELECT COUNT(*) FROM execution_action",Integer.class));
            assertEquals(0,f.migrate().migrationsExecuted);
        }
    }
}
