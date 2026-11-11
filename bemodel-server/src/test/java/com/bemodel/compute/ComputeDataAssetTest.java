package com.bemodel.compute;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="COMPUTE_ONTOLOGY_MYSQL_URL",matches="jdbc:mysql:.*")
class ComputeDataAssetTest {
    @Test void registersTenSourcesAndNeverExposesSensitiveRowsToAgent() throws Exception {
        try(var db=ComputeMysqlTestDatabase.create()) {
            Flyway.configure().dataSource(db.platformUrl(),ComputeMysqlTestDatabase.username(),ComputeMysqlTestDatabase.password()).locations("classpath:db/compute-platform").load().migrate();
            var s=new ComputeDataAssetService(db.platform(),ComputeRuleEvaluatorTest.JSON);
            var assets=s.list(false);assertEquals(10,assets.size());
            var physical=new HashSet<>(db.business().query("SELECT table_name,column_name FROM information_schema.columns WHERE table_schema=?",(rs,n)->rs.getString(1)+"."+rs.getString(2),db.businessName()));
            assertEquals("SIMULATED",s.require("GPU_OBSERVATION").sourceKind());
            assertEquals("EXTERNAL_PENDING",s.require("K8S_STATE").sourceKind());
            for(var a:assets) {
                assertFalse(a.owner().isBlank());assertFalse(a.authoritativeSource().isBlank());assertFalse(a.updateRequirement().isBlank());assertTrue(a.fields().size()>0,a.assetCode());
                for(var f:a.fields()) {
                    assertFalse(f.path("meaning").asText().isBlank());
                    String table=f.path("table").asText();
                    if(!"EXTERNAL_CONTRACT".equals(table)) {
                        assertTrue(physical.contains(table+"."+f.path("column").asText()),f.toString());
                    }
                }
            }
            assertTrue(s.list(true).stream().noneMatch(a->"ACCOUNT_OPERATION_LOG".equals(a.assetCode())||"IDENTITY_ORGANIZATION".equals(a.assetCode())));
            assertTrue(s.list(true).stream().allMatch(a->{for(var f:a.fields()) if("SENSITIVE".equals(f.path("sensitivity").asText())) return false;return true;}));
            var raw=new HashMap<String,Object>();raw.put("id",2L);raw.put("image_ref","secret-registry");raw.put("command_args",List.of("token"));raw.put("memory_bytes",1024L);raw.put("unregistered_field","secret");
            var safe=s.projectForAgent("DEPLOYMENT_BASELINE","deployment_revision",raw);
            assertEquals(Map.of("id",2L,"memory_bytes",1024L),safe);
            assertTrue(s.projectForAgent("IDENTITY_ORGANIZATION","employee",Map.of("email","private","id",1)).isEmpty());
            assertEquals(false,s.collectionContract().get("ingestionEnabled"));
        }
    }
    @Test void editsAreVersionedAuditedAndRollbackWithAuditFailure() throws Exception {
        try(var db=ComputeMysqlTestDatabase.create()) {
            Flyway.configure().dataSource(db.platformUrl(),ComputeMysqlTestDatabase.username(),ComputeMysqlTestDatabase.password()).locations("classpath:db/compute-platform").load().migrate();
            var s=new ComputeDataAssetService(db.platform(),ComputeRuleEvaluatorTest.JSON);
            var tx=new TransactionTemplate(new DataSourceTransactionManager(db.platform().getDataSource()));
            var body=ComputeRuleEvaluatorTest.JSON.valueToTree(s.require("GPU_OBSERVATION"));
            ((com.fasterxml.jackson.databind.node.ObjectNode)body).put("owner","test owner");
            tx.execute(status->s.update("GPU_OBSERVATION",body));
            assertEquals(2,s.require("GPU_OBSERVATION").version());assertEquals("test owner",s.require("GPU_OBSERVATION").owner());
            assertThrows(RuntimeException.class,()->tx.execute(status->s.update("GPU_OBSERVATION",body)));
            var newer=ComputeRuleEvaluatorTest.JSON.valueToTree(s.require("GPU_OBSERVATION"));
            ((com.fasterxml.jackson.databind.node.ObjectNode)newer).put("maxAgeSeconds",0);
            assertThrows(RuntimeException.class,()->tx.execute(status->s.update("GPU_OBSERVATION",newer)));
            ((com.fasterxml.jackson.databind.node.ObjectNode)newer).put("maxAgeSeconds",30).put("owner","should rollback");
            db.platform().execute("CREATE TRIGGER test_audit_failure BEFORE INSERT ON bm_compute_operation_audit FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected audit failure'");
            assertThrows(RuntimeException.class,()->tx.execute(status->s.update("GPU_OBSERVATION",newer)));
            assertEquals("test owner",s.require("GPU_OBSERVATION").owner());assertEquals(2,s.require("GPU_OBSERVATION").version());
            assertEquals(1,db.platform().queryForObject("SELECT COUNT(*) FROM bm_compute_operation_audit WHERE operation='DATA_ASSET_UPDATED'",Integer.class));
        }
    }
}
