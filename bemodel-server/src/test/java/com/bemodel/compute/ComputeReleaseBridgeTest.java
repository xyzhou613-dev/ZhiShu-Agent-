package com.bemodel.compute;

import com.bemodel.modeling.entity.Release;
import com.bemodel.modeling.entity.Rule;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="COMPUTE_ONTOLOGY_MYSQL_URL",matches="jdbc:mysql:.*")
class ComputeReleaseBridgeTest {
    static ComputeReleaseBridge bridge(ComputeMysqlTestDatabase db) {
        Flyway.configure().dataSource(db.platformUrl(),ComputeMysqlTestDatabase.username(),ComputeMysqlTestDatabase.password()).locations("classpath:db/compute-platform").load().migrate();
        return new ComputeReleaseBridge(db.platform(),new ComputeDatabase.Store(db.business(),null),new ComputeRuleService(null,ComputeRuleEvaluatorTest.JSON),ComputeRuleEvaluatorTest.JSON,new DataSourceTransactionManager(db.platform().getDataSource()),1,"a800-demo");
    }
    static List<Rule> rules() {
        return ComputeRuleEvaluator.CODES.stream().map(code->{var r=ComputeRuleEvaluatorTest.rule(code); r.setStatus("PUBLISHED"); return r;}).toList();
    }
    static long publish(ComputeMysqlTestDatabase db,ComputeReleaseBridge bridge,Map<String,Object> config,String tag) {
        return new TransactionTemplate(new DataSourceTransactionManager(db.platform().getDataSource())).execute(status->{
            bridge.lockPublication();
            try {
                String payload=ComputeRuleEvaluatorTest.JSON.writeValueAsString(Map.of("compute",config));
                long id=ComputeReleaseBridge.insert(db.platform(),"INSERT INTO bm_release(version_tag,snapshot_json) VALUES(?,?)",tag,payload);
                var r=new Release();r.setId(id);r.setVersionTag(tag);r.setSnapshotJson(payload);bridge.stage(r);return id;
            }catch(Exception e){throw new RuntimeException(e);}
        });
    }
    @Test void partialCopyRollsBackAndRetryDoesNotDuplicateOrChangeHistory() throws Exception {
        try(var db=ComputeMysqlTestDatabase.create()) {
            var b=bridge(db); var j=db.business();
            j.execute("CREATE TRIGGER test_fail_rule BEFORE INSERT ON rule_version FOR EACH ROW BEGIN IF NEW.rule_code='R-14' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test injected failure'; END IF; END");
            long release=publish(db,b,b.prepare(rules()),"v1.0");
            assertEquals("FAILED",db.platform().queryForObject("SELECT status FROM bm_compute_release_sync WHERE release_id=?",String.class,release));
            assertNull(j.queryForObject("SELECT rule_set_id FROM active_rule_set WHERE id=1",Long.class));
            assertEquals(0,j.queryForObject("SELECT COUNT(*) FROM rule_version",Integer.class));
            assertEquals(0,j.queryForObject("SELECT COUNT(*) FROM rule_set",Integer.class));
            j.execute("DROP TRIGGER test_fail_rule");
            assertEquals("SYNCED",b.sync(release).get("status"));
            var previous=j.queryForList("SELECT * FROM rule_version ORDER BY id");
            b.sync(release); b.sync(release);
            assertEquals(14,j.queryForObject("SELECT COUNT(*) FROM rule_version",Integer.class));
            assertEquals(1,j.queryForObject("SELECT COUNT(*) FROM rule_activation",Integer.class));
            assertThrows(Exception.class,()->j.update("UPDATE rule_version SET version=99 WHERE id=(SELECT x FROM (SELECT MIN(id) x FROM rule_version)t)"));
            long newer=publish(db,b,b.prepare(rules()),"v1.1");
            assertEquals("SYNCED",b.sync(newer).get("status"));
            assertEquals(28,j.queryForObject("SELECT COUNT(*) FROM rule_version",Integer.class));
            assertEquals(previous,j.queryForList("SELECT * FROM rule_version ORDER BY id LIMIT 14"));
            b.sync(release);
            assertEquals(newer,((Number)b.active().get("source_release_id")).longValue());
            assertEquals(2,j.queryForObject("SELECT COUNT(*) FROM rule_activation",Integer.class));
        }
    }
    @Test void stalePublicationCannotOverwriteANewerActiveSetAndPlatformRollbackDoesNotActivate() throws Exception {
        try(var db=ComputeMysqlTestDatabase.create()) {
            var b=bridge(db); var oldConfig=b.prepare(rules());
            var tx=new TransactionTemplate(new DataSourceTransactionManager(db.platform().getDataSource()));
            tx.execute(status->{
                b.lockPublication();
                try { var r=new Release(); r.setId(ComputeReleaseBridge.insert(db.platform(),"INSERT INTO bm_release(version_tag,snapshot_json) VALUES('rollback',?)",ComputeRuleEvaluatorTest.JSON.writeValueAsString(Map.of("compute",oldConfig))));r.setVersionTag("rollback");r.setSnapshotJson(ComputeRuleEvaluatorTest.JSON.writeValueAsString(Map.of("compute",oldConfig)));b.stage(r);status.setRollbackOnly();return null; }
                catch(Exception e){throw new RuntimeException(e);}
            });
            assertEquals(0,db.business().queryForObject("SELECT COUNT(*) FROM rule_activation",Integer.class));
            assertEquals(0,db.platform().queryForObject("SELECT COUNT(*) FROM bm_release",Integer.class));
            assertEquals(0,db.platform().queryForObject("SELECT COUNT(*) FROM bm_compute_release_sync",Integer.class));
            long first=publish(db,b,oldConfig,"v1.0");
            long stale=publish(db,b,oldConfig,"v1.1");
            assertEquals("FAILED",b.sync(stale).get("status"));
            assertEquals(first,((Number)b.active().get("source_release_id")).longValue());
            assertEquals(14,db.business().queryForObject("SELECT COUNT(*) FROM rule_version",Integer.class));
            db.platform().update("UPDATE bm_release SET snapshot_json='{}' WHERE id=?",first);
            assertEquals("FAILED",b.sync(first).get("status"));
            assertEquals(first,((Number)b.active().get("source_release_id")).longValue());
        }
    }
    @Test void rejectsMissingDraftUnknownImplementationAndUnresolvedConflicts() throws Exception {
        try(var db=ComputeMysqlTestDatabase.create()) {
            var b=bridge(db);var rules=rules();
            assertThrows(RuntimeException.class,()->b.prepare(rules.subList(0,13)));
            rules.getFirst().setStatus("DRAFT");assertThrows(RuntimeException.class,()->b.prepare(rules));rules.getFirst().setStatus("PUBLISHED");
            String saved=rules.getFirst().getExprJson();rules.getFirst().setExprJson(saved.replace(ComputeRuleEvaluator.VERSION,"unknown-build"));
            assertThrows(RuntimeException.class,()->b.prepare(rules));rules.getFirst().setExprJson(saved);
            db.platform().update("INSERT INTO bm_compute_rule_conflict(rule_codes,description,priority,created_by) VALUES('[\"R-02\",\"R-15\"]','conflict','HIGH','test')");
            assertThrows(RuntimeException.class,()->b.prepare(rules));
            db.platform().update("UPDATE bm_compute_rule_conflict SET status='RESOLVED',resolution='verified',resolved_by='test',resolved_at=UTC_TIMESTAMP(6)");
            assertNotNull(b.prepare(rules));
            db.business().update("UPDATE task_type SET priority=1 WHERE id=1");
            assertThrows(RuntimeException.class,()->b.prepare(rules));
        }
    }
}
