package com.bemodel.compute;

import com.bemodel.common.BizException;
import com.bemodel.modeling.entity.Rule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.bemodel.compute.ComputeRuleEvaluator.Status.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ComputeRuleRunnerTest {
    private final ComputeReleaseBridge bridge = mock(ComputeReleaseBridge.class);
    private final ComputeRuleRunner runner = new ComputeRuleRunner(bridge,
            new ComputeRuleService(null, ComputeRuleEvaluatorTest.JSON), ComputeRuleEvaluatorTest.JSON);

    private Map<String, Object> fixture(int maxAge) throws Exception {
        var rules = new ArrayList<Rule>();
        var versions = new ArrayList<Map<String, Object>>();
        for (String code : ComputeRuleEvaluator.CODES) {
            Rule rule = ComputeRuleEvaluatorTest.rule(code);
            rule.setStatus("PUBLISHED");
            var definition = (ObjectNode) ComputeRuleEvaluatorTest.JSON.readTree(rule.getExprJson());
            if ("R-11".equals(code)) ((ObjectNode) definition.path("parameters")).put("maxAgeSeconds", maxAge);
            rule.setExprJson(definition.toString());
            rules.add(rule);
            versions.add(new HashMap<>(Map.of("id", versions.size() + 1, "rule_code", code,
                    "member_rule_code", code, "version", 3, "source_release_id", 12,
                    "evaluator_version", ComputeRuleEvaluator.VERSION, "definition", rule.getExprJson(),
                    "stage", definition.path("stage").asText(), "rule_type", definition.path("kind").asText())));
        }
        return Map.of("set", new HashMap<>(Map.of("id", 4, "source_release_id", 12, "source_version_tag", "v1.0",
                "evaluator_version", ComputeRuleEvaluator.VERSION, "formula_version", ComputeVersions.FORMULAS,
                "settings", ComputeRuleEvaluatorTest.JSON.writeValueAsString(Map.of("rules", rules)))),
                "rules", versions);
    }

    private ComputeRuleRunner.Snapshot activate(int maxAge) throws Exception {
        when(bridge.active()).thenReturn(Map.of("id", 4));
        when(bridge.snapshot(4)).thenReturn(fixture(maxAge));
        return runner.pinActive();
    }

    static ObjectNode facts(String stage) {
        var facts = ComputeRuleEvaluatorTest.JSON.createObjectNode();
        for (String code : ComputeRuleRunner.STAGES.get(stage)) {
            facts.set(code, ComputeRuleEvaluatorTest.FIXTURES.get(code).path("definition").path("sampleFacts").deepCopy());
        }
        return facts;
    }

    @Test
    void executesAllFourteenRulesWithPinnedExecutionVersions() throws Exception {
        var pinned = activate(30);
        int count = 0;
        for (String stage : ComputeRuleRunner.STAGES.keySet()) {
            var result = runner.evaluate(pinned, stage, facts(stage));
            assertEquals(PASS, result.overallResult(), result.toString());
            count += result.checks().size();
            for (var check : result.checks()) {
                assertEquals(3, check.ruleVersion());
                assertEquals(7, check.definitionVersion());
                assertEquals(PASS, check.result());
            }
        }
        assertEquals(14, count);
    }

    @Test
    void pinsParametersEvenWhenTheActiveSetChanges() throws Exception {
        var pinned = activate(5);
        when(bridge.active()).thenReturn(Map.of("id", 99));
        when(bridge.snapshot(99)).thenReturn(fixture(30));
        var facts = facts("ADMISSION");
        facts.withObject("/R-11").put("asOf", "2026-11-06T10:00:20Z");
        var result = runner.evaluate(pinned, "ADMISSION", facts);
        assertEquals(FAIL, result.overallResult());
        assertEquals("QUEUE_STALE_EVIDENCE", result.checks().get(1).reasonCode());
        assertEquals(4, result.snapshot().ruleSetId());
        verify(bridge, times(1)).active();
        verify(bridge, never()).snapshot(99);
    }

    @Test
    void keepsMissingFactsAndClosureBranchesVisible() throws Exception {
        var pinned = activate(30);
        var empty = runner.evaluate(pinned, "ADMISSION", ComputeRuleEvaluatorTest.JSON.createObjectNode());
        assertEquals(ERROR, empty.overallResult());
        assertEquals(FAIL, empty.checks().get(0).result());
        assertEquals(ERROR, empty.checks().get(1).result());
        var closure = runner.evaluate(pinned, "CLOSURE", facts("CLOSURE"));
        assertEquals(PASS, closure.overallResult());
        assertNotNull(closure.checks().getFirst().output().get("outcome"));
        var submission = facts("SUBMISSION");
        submission.withObject("/R-13").put("preemptionRequired", false);
        var result = runner.evaluate(pinned, "SUBMISSION", submission);
        assertEquals(NOT_APPLICABLE, result.checks().getLast().result());
        assertEquals(PASS, result.overallResult());
        assertThrows(BizException.class, () -> runner.evaluate(pinned, null, submission));
        assertThrows(BizException.class, () -> runner.evaluate(pinned, "ADMISSION", submission));
    }

    @SuppressWarnings("unchecked")
    @Test
    void rejectsMissingIncompleteAndIncompatibleSnapshots() throws Exception {
        when(bridge.active()).thenReturn(Map.of("active", false));
        assertThrows(BizException.class, runner::pinActive);
        when(bridge.active()).thenReturn(Map.of("id", 4));
        var incomplete = fixture(30);
        ((List<?>) incomplete.get("rules")).removeLast();
        when(bridge.snapshot(4)).thenReturn(incomplete);
        assertThrows(BizException.class, runner::pinActive);
        var incompatible = fixture(30);
        ((Map<String, Object>) incompatible.get("set")).put("evaluator_version", "future-build");
        when(bridge.snapshot(4)).thenReturn(incompatible);
        assertThrows(BizException.class, runner::pinActive);
        var mismatched = fixture(30);
        ((List<Map<String, Object>>) mismatched.get("rules")).getFirst().put("member_rule_code", "R-09");
        when(bridge.snapshot(4)).thenReturn(mismatched);
        assertThrows(BizException.class, runner::pinActive);
    }
}
