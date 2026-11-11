package com.bemodel.compute;

import com.bemodel.modeling.entity.Rule;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in: the helper creates and removes only random, guarded m01_test_* schemas. */
@EnabledIfEnvironmentVariable(named = "COMPUTE_ONTOLOGY_MYSQL_URL", matches = "jdbc:mysql:.*")
class ComputeRuleMigrationTest {
    private Flyway migration(ComputeMysqlTestDatabase db, String target) {
        return Flyway.configure().dataSource(db.platformUrl(), ComputeMysqlTestDatabase.username(), ComputeMysqlTestDatabase.password())
                .locations("classpath:db/compute-platform").target(target).load();
    }

    @Test void upgradesFiveDraftsInPlaceAndAddsNineWithoutActivatingAnything() throws Exception {
        try (var db = ComputeMysqlTestDatabase.create()) {
            migration(db,"5").migrate();
            JdbcTemplate jdbc = db.platform();
            var previousIds = jdbc.queryForList("SELECT id,rule_code FROM bm_rule ORDER BY id");
            var mappings = jdbc.queryForList("SELECT * FROM bm_mapping ORDER BY id");
            var holds = db.business().queryForList("SELECT * FROM hold_period ORDER BY id");
            assertEquals(1,migration(db,"6").migrate().migrationsExecuted);
            assertEquals(14,jdbc.queryForObject("SELECT COUNT(*) FROM bm_rule WHERE engine='COMPUTE'",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM bm_rule WHERE rule_code='R-09'",Integer.class));
            assertEquals(14,jdbc.queryForObject("SELECT COUNT(*) FROM bm_rule WHERE status='DRAFT'",Integer.class));
            for (var old : previousIds) {
                assertEquals(old.get("id"),jdbc.queryForObject("SELECT id FROM bm_rule WHERE rule_code=?",Long.class,old.get("rule_code")));
                assertEquals(2,jdbc.queryForObject("SELECT version FROM bm_rule WHERE rule_code=?",Integer.class,old.get("rule_code")));
            }
            var service = new ComputeRuleService(null,ComputeRuleEvaluatorTest.JSON);
            for (var row : jdbc.queryForList("SELECT * FROM bm_rule")) {
                Rule rule = ComputeRuleEvaluatorTest.rule((String)row.get("rule_code"));
                rule.setExprJson((String)row.get("expr_json"));
                var definition = service.validate(rule);
                assertEquals(ComputeRuleEvaluatorTest.FIXTURES.get(rule.getRuleCode()).path("definition"),definition);
                var check = new ComputeRuleEvaluator().evaluate(rule,definition,definition.path("sampleFacts"));
                assertEquals(ComputeRuleEvaluator.Status.PASS,check.result(),rule.getRuleCode());
            }
            assertEquals(mappings,jdbc.queryForList("SELECT * FROM bm_mapping ORDER BY id"));
            assertEquals(holds,db.business().queryForList("SELECT * FROM hold_period ORDER BY id"));
            assertEquals(0,db.business().queryForObject("SELECT COUNT(*) FROM rule_version",Integer.class));
            assertEquals(0,db.business().queryForObject("SELECT COUNT(*) FROM execution_action",Integer.class));
            assertEquals(0,migration(db,"6").migrate().migrationsExecuted);
        }
    }

    @Test void editedOrPublishedLegacyRulesAreNotSilentlyOverwritten() throws Exception {
        try (var db = ComputeMysqlTestDatabase.create()) {
            migration(db,"5").migrate();
            var jdbc=db.platform();
            jdbc.update("UPDATE bm_rule SET expression='自定义口径' WHERE rule_code='R-11'");
            jdbc.update("UPDATE bm_rule SET status='PUBLISHED',version=3 WHERE rule_code='R-04'");
            jdbc.update("UPDATE bm_rule SET name='自定义名称' WHERE rule_code='R-07'");
            var edited=jdbc.queryForMap("SELECT * FROM bm_rule WHERE rule_code='R-11'");
            var published=jdbc.queryForMap("SELECT * FROM bm_rule WHERE rule_code='R-04'");
            var renamed=jdbc.queryForMap("SELECT * FROM bm_rule WHERE rule_code='R-07'");
            migration(db,"6").migrate();
            assertEquals(edited,jdbc.queryForMap("SELECT * FROM bm_rule WHERE rule_code='R-11'"));
            assertEquals(published,jdbc.queryForMap("SELECT * FROM bm_rule WHERE rule_code='R-04'"));
            assertEquals(renamed,jdbc.queryForMap("SELECT * FROM bm_rule WHERE rule_code='R-07'"));
            assertEquals(14,jdbc.queryForObject("SELECT COUNT(*) FROM bm_rule",Integer.class));
            assertEquals(11,jdbc.queryForObject("SELECT COUNT(*) FROM bm_rule WHERE engine='COMPUTE'",Integer.class));
        }
    }
}
