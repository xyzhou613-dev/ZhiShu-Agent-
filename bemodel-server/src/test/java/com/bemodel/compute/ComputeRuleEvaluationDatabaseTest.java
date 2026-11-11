package com.bemodel.compute;

import com.bemodel.common.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static com.bemodel.compute.ComputeReleaseBridgeTest.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "COMPUTE_ONTOLOGY_MYSQL_URL", matches = "jdbc:mysql:.*")
class ComputeRuleEvaluationDatabaseTest {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = ComputeRuleEvaluatorTest.JSON;

    private ComputeRuleEvaluationService service(ComputeMysqlTestDatabase db, ComputeReleaseBridge bridge) {
        var runner = new ComputeRuleRunner(bridge, new ComputeRuleService(null, JSON), JSON);
        var store = new ComputeDatabase.Store(db.business(), null);
        return new ComputeRuleEvaluationService(store, runner, JSON, new ComputeEvidenceService(store, runner, JSON));
    }

    private ObjectNode request(String stage) {
        var body = JSON.createObjectNode().put("stage", stage);
        body.set("facts", ComputeRuleRunnerTest.facts(stage));
        return body;
    }

    @Test
    void persistsAllStagesAndKeepsOldVersionsAfterANewPublication() throws Exception {
        try (var db = ComputeMysqlTestDatabase.create()) {
            var bridge = bridge(db);
            var service = service(db, bridge);
            assertThrows(BizException.class, () -> service.execute(request("ADMISSION")));
            assertEquals(0, db.business().queryForObject("SELECT COUNT(*) FROM rule_evaluation_run", Integer.class));
            publish(db, bridge, bridge.prepare(rules()), "eval-v1");
            ComputeRuleEvaluationService.Detail original = null;
            for (String stage : ComputeRuleRunner.STAGES.keySet()) {
                var detail = service.execute(request(stage));
                assertEquals(ComputeRuleEvaluator.Status.PASS, detail.run().overallResult(), detail.toString());
                assertFalse(detail.run().authorizesExecution());
                assertEquals("CALLER", detail.run().inputSource());
                assertEquals(ComputeRuleRunner.STAGES.get(stage).size(), detail.checks().size());
                assertEquals(detail, service.detail(detail.run().id()));
                if ("ADMISSION".equals(stage)) original = detail;
            }
            assertNotNull(original);
            assertEquals(14, db.business().queryForObject("SELECT COUNT(*) FROM rule_evaluation_check", Integer.class));
            var nextRules = rules();
            var freshness = nextRules.stream().filter(rule -> "R-11".equals(rule.getRuleCode())).findFirst().orElseThrow();
            var definition = (ObjectNode) JSON.readTree(freshness.getExprJson());
            definition.withObject("/parameters").put("maxAgeSeconds", 1);
            freshness.setExprJson(definition.toString());
            freshness.setVersion(freshness.getVersion() + 1);
            publish(db, bridge, bridge.prepare(nextRules), "eval-v2");
            var input = request("ADMISSION");
            input.withObject("/facts/R-11").put("asOf", "2026-11-06T10:00:20Z");
            var newer = service.execute(input);
            assertEquals(ComputeRuleEvaluator.Status.FAIL, newer.run().overallResult());
            assertNotEquals(original.run().ruleSetId(), newer.run().ruleSetId());
            assertEquals(original, service.detail(original.run().id()));
            assertEquals(7, service.list(1, 20).getTotal());
            assertEquals(newer.run().id(), service.list(1, 1).getList().getFirst().id());
            var old = original;
            assertThrows(Exception.class, () -> db.business().update("""
                    INSERT INTO rule_evaluation_check
                    (run_id,rule_set_id,rule_version_id,definition_version,result,reason_code,output)
                    VALUES (?,?,?,1,'PASS','PASS','{}')
                    """, old.run().id(), old.run().ruleSetId(), newer.checks().getFirst().ruleVersionId()));
            assertEquals(1, db.business().queryForObject("SELECT COUNT(*) FROM compute_request", Integer.class));
            assertEquals(0, db.business().queryForObject("SELECT COUNT(*) FROM decision", Integer.class));
        }
    }

    @Test
    void rollsBackTheHeaderAndChecksAndRecordsInvalidRuleFacts() throws Exception {
        try (var db = ComputeMysqlTestDatabase.create()) {
            var bridge = bridge(db);
            publish(db, bridge, bridge.prepare(rules()), "eval-v1");
            var service = service(db, bridge);
            var jdbc = db.business();
            long freshness = jdbc.queryForObject("SELECT id FROM rule_version WHERE rule_code='R-11'", Long.class);
            jdbc.execute("CREATE TRIGGER test_fail_evaluation BEFORE INSERT ON rule_evaluation_check FOR EACH ROW "
                    + "BEGIN IF NEW.rule_version_id=" + freshness
                    + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected evaluation write failure'; END IF; END");
            assertThrows(Exception.class, () -> service.execute(request("ADMISSION")));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM rule_evaluation_run", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM rule_evaluation_check", Integer.class));
            jdbc.execute("DROP TRIGGER test_fail_evaluation");
            var missing = JSON.createObjectNode().put("stage", "ADMISSION");
            missing.set("facts", JSON.createObjectNode());
            var detail = service.execute(missing);
            assertEquals(ComputeRuleEvaluator.Status.ERROR, detail.run().overallResult());
            assertEquals(ComputeRuleEvaluator.Status.FAIL, detail.checks().getFirst().result());
            assertEquals(ComputeRuleEvaluator.Status.ERROR, detail.checks().getLast().result());
            var wrong = request("ADMISSION");
            wrong.withObject("/facts").put("R-11", false);
            assertEquals(ComputeRuleEvaluator.Status.ERROR, service.execute(wrong).run().overallResult());
            long total = service.list(1, 20).getTotal();
            assertThrows(BizException.class, () -> service.execute(request("ADMISSION").put("stage", "UNKNOWN")));
            wrong.withObject("/facts").put("R-09", "unexpected");
            assertThrows(BizException.class, () -> service.execute(wrong));
            assertEquals(total, service.list(1, 20).getTotal());
            assertThrows(BizException.class, () -> service.detail(Long.MAX_VALUE));
            assertEquals(0, Flyway.configure().dataSource(db.businessUrl(), ComputeMysqlTestDatabase.username(),
                    ComputeMysqlTestDatabase.password()).locations("classpath:db/a800-compute").load()
                    .migrate().migrationsExecuted);
        }
    }
}
