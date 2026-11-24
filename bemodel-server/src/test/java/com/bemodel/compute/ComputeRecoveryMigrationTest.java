package com.bemodel.compute;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.bemodel.common.BizException;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="COMPUTE_ONTOLOGY_MYSQL_URL",matches="jdbc:mysql:.*")
class ComputeRecoveryMigrationTest {
 @Test void upgradesWithoutInventingRequirementsOrRewritingLegacyFlows()throws Exception{
  try(var db=ComputeMysqlTestDatabase.create("14")){var j=db.business();long snapshot=ComputeReleaseBridge.insert(j,"INSERT INTO evidence_snapshot(as_of) VALUES(UTC_TIMESTAMP(6))");long decision=ComputeReleaseBridge.insert(j,"INSERT INTO decision(request_id,kind,outcome,as_of,snapshot_id) SELECT 1,'SUBMISSION','APPROVED',as_of,id FROM evidence_snapshot WHERE id=?",snapshot);long workflow=ComputeReleaseBridge.insert(j,"INSERT INTO workflow_run(request_id,decision_id,group_id,job_id,restore_revision_id,step) VALUES(1,?,2,1,2,'LEGACY')",decision);
   var before=j.queryForMap("SELECT * FROM deployment_revision WHERE id=2");var migration=Flyway.configure().dataSource(db.businessUrl(),ComputeMysqlTestDatabase.username(),ComputeMysqlTestDatabase.password()).locations("classpath:db/a800-compute").target("15").load();assertEquals(1,migration.migrate().migrationsExecuted);assertEquals(0,migration.migrate().migrationsExecuted);assertEquals(before,j.queryForMap("SELECT * FROM deployment_revision WHERE id=2"));assertEquals("LEGACY",j.queryForObject("SELECT step FROM workflow_run WHERE id=?",String.class,workflow));assertEquals(0,j.queryForObject("SELECT COUNT(*) FROM deployment_recovery_spec",Integer.class));assertNull(j.queryForObject("SELECT restore_spec_id FROM workflow_run WHERE id=?",Long.class,workflow));
   var specs=new ComputeRecoverySpecService(new ComputeDatabase.Store(j,null));var json=new com.fasterxml.jackson.databind.ObjectMapper();var b=json.createObjectNode().put("revisionId",2).put("vramBytesPerGpu",1073741824).put("gpuInterconnect","NONE").put("declaration","Explicit prospective test declaration");assertThrows(BizException.class,()->specs.register(2,b));b.put("harnessTeamId",1);long id=((Number)specs.register(2,b).get("id")).longValue();assertThrows(Exception.class,()->j.update("UPDATE deployment_recovery_spec SET declaration='overwritten' WHERE id=?",id));assertThrows(Exception.class,()->j.update("DELETE FROM deployment_recovery_spec WHERE id=?",id));assertThrows(Exception.class,()->j.update("UPDATE deployment_revision SET memory_bytes=1 WHERE id=2"));
  }
 }
}
