package com.bemodel.compute;

import com.bemodel.common.BizException;
import com.bemodel.modeling.entity.Rule;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.bemodel.compute.ComputeRuleEvaluator.Status;

@Service
@Profile("compute")
final class ComputeRuleRunner {
    static final Map<String, List<String>> STAGES = Map.of(
            "ADMISSION", List.of("R-01", "R-11"),
            "PLANNING", List.of("R-02", "R-03", "R-04", "R-14"),
            "SUBMISSION", List.of("R-07", "R-12", "R-13"),
            "EXECUTION", List.of("R-05"),
            "CLOSURE", List.of("R-06", "R-15"),
            "GLOBAL", List.of("R-08", "R-10"));

    record ExecutionRule(long ruleVersionId, String ruleCode, int ruleVersion, int definitionVersion,
                         String stage, JsonNode definition) {}
    record Snapshot(long ruleSetId, long sourceReleaseId, String sourceVersionTag, String evaluatorVersion,
                    String formulaVersion, List<ExecutionRule> rules) {}
    record Check(long ruleVersionId, String ruleCode, int ruleVersion, int definitionVersion,
                 Status result, String reasonCode, Map<String, Object> output) {}
    record Evaluation(Snapshot snapshot, String stage, Status overallResult, List<Check> checks) {}

    private final ComputeReleaseBridge releases;
    private final ComputeRuleService definitions;
    private final ObjectMapper json;
    private final ComputeRuleEvaluator evaluator = new ComputeRuleEvaluator();

    ComputeRuleRunner(ComputeReleaseBridge releases, ComputeRuleService definitions, ObjectMapper json) {
        this.releases = releases;
        this.definitions = definitions;
        this.json = json;
    }

    Snapshot pinActive() {
        Object activeId = releases.active().get("id");
        if (!(activeId instanceof Number number) || number.longValue() <= 0) {
            throw new BizException("尚未激活算力规则集合，请先发布并同步规则");
        }
        long setId = number.longValue();
        Map<String, Object> snapshot = releases.snapshot(setId);
        JsonNode set = metadata(snapshot.get("set"), "id", "source_release_id", "source_version_tag",
                "evaluator_version", "formula_version", "settings");
        if (!(snapshot.get("rules") instanceof List<?> versions)) {
            throw new BizException("规则执行快照不完整");
        }
        if (set.path("id").asLong() != setId
                || !ComputeRuleEvaluator.VERSION.equals(set.path("evaluator_version").asText())
                || !ComputeVersions.FORMULAS.equals(set.path("formula_version").asText())) {
            throw new BizException("生效集合的规则或公式实现版本不兼容");
        }
        try {
            JsonNode settings = json.readTree(set.path("settings").asText());
            if (settings == null || !settings.path("rules").isArray()) {
                throw new BizException("规则集合缺少发布定义");
            }
            Map<String, Rule> sourceRules = new HashMap<>();
            for (JsonNode entry : settings.path("rules")) {
                Rule rule = json.treeToValue(entry, Rule.class);
                if (sourceRules.put(rule.getRuleCode(), rule) != null) {
                    throw new BizException("规则快照包含重复定义");
                }
            }
            if (!sourceRules.keySet().equals(Set.copyOf(ComputeRuleEvaluator.CODES))) {
                throw new BizException("生效集合缺少完整的 14 条规则定义");
            }
            var checked = new ArrayList<ExecutionRule>();
            var seen = new java.util.HashSet<String>();
            var ids = new java.util.HashSet<Long>();
            for (Object row : versions) {
                JsonNode version = metadata(row, "id", "rule_code", "member_rule_code", "version",
                        "source_release_id", "evaluator_version", "definition", "stage", "rule_type");
                String code = version.path("rule_code").asText();
                Rule source = sourceRules.get(code);
                if (source == null || !seen.add(code) || !code.equals(version.path("member_rule_code").asText())
                        || !ids.add(version.path("id").asLong()) || version.path("id").asLong() <= 0
                        || version.path("version").asInt() <= 0 || !"PUBLISHED".equals(source.getStatus())
                        || version.path("source_release_id").asLong() != set.path("source_release_id").asLong()
                        || !ComputeRuleEvaluator.VERSION.equals(version.path("evaluator_version").asText())) {
                    throw new BizException("规则执行快照身份或实现版本不兼容");
                }
                JsonNode definition = json.readTree(version.path("definition").asText());
                JsonNode original = definitions.validate(source);
                if (!original.equals(definition) || !definition.path("stage").asText().equals(version.path("stage").asText())
                        || !definition.path("kind").asText().equals(version.path("rule_type").asText())) {
                    throw new BizException("规则执行快照与发布定义不一致");
                }
                checked.add(new ExecutionRule(version.path("id").asLong(), code, version.path("version").asInt(),
                        source.getVersion(), version.path("stage").asText(), definition));
            }
            if (!seen.equals(Set.copyOf(ComputeRuleEvaluator.CODES))) {
                throw new BizException("规则执行快照不完整");
            }
            return new Snapshot(setId, set.path("source_release_id").asLong(), set.path("source_version_tag").asText(),
                    set.path("evaluator_version").asText(), set.path("formula_version").asText(), List.copyOf(checked));
        } catch (JsonProcessingException e) {
            throw new BizException("规则执行快照无法解析");
        }
    }

    private JsonNode metadata(Object row, String... fields) {
        if (!(row instanceof Map<?, ?> values)) {
            throw new BizException("规则执行快照格式无效");
        }
        var node = json.createObjectNode();
        for (String field : fields) {
            node.set(field, json.valueToTree(values.get(field)));
        }
        return node;
    }

    Evaluation evaluate(Snapshot snapshot, String stage, JsonNode facts) {
        List<String> codes = validateInput(stage, facts);
        var checks = new ArrayList<Check>();
        for (String code : codes) {
            ExecutionRule rule = snapshot.rules().stream().filter(item -> code.equals(item.ruleCode()))
                    .findFirst().orElseThrow(() -> new BizException("阶段规则快照缺失"));
            if (!stage.equals(rule.stage())) {
                throw new BizException("阶段与规则执行快照不匹配");
            }
            var result = evaluator.evaluateSnapshot(code, rule.ruleVersion(), rule.definition(), facts.path(code));
            checks.add(new Check(rule.ruleVersionId(), code, rule.ruleVersion(), rule.definitionVersion(),
                    result.result(), result.reasonCode(), result.output()));
        }
        return new Evaluation(snapshot, stage, summarize(checks), List.copyOf(checks));
    }

    static List<String> validateInput(String stage, JsonNode facts) {
        List<String> codes = stage == null ? null : STAGES.get(stage);
        if (codes == null) {
            throw new BizException("未知规则运行阶段");
        }
        if (facts == null || !facts.isObject() || facts.toString().length() > 65536) {
            throw new BizException("facts 必须是 JSON 对象，且不超过 64K 字符");
        }
        facts.fieldNames().forEachRemaining(code -> {
            if (!codes.contains(code)) {
                throw new BizException("facts 包含不属于该阶段的规则编号");
            }
        });
        return codes;
    }

    private static Status summarize(List<Check> checks) {
        if (checks.stream().anyMatch(check -> check.result() == Status.ERROR)) return Status.ERROR;
        if (checks.stream().anyMatch(check -> check.result() == Status.FAIL)) return Status.FAIL;
        if (checks.stream().anyMatch(check -> check.result() == Status.PASS)) return Status.PASS;
        return Status.NOT_APPLICABLE;
    }
}
