package com.bemodel.compute;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bemodel.common.BizException;
import com.bemodel.modeling.entity.Rule;
import com.bemodel.modeling.mapper.RuleMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@Profile("compute")
public class ComputeRuleService {
    static final Map<String, String> HANDLERS = Map.ofEntries(
            Map.entry("R-01", "specification"), Map.entry("R-02", "capacity"), Map.entry("R-03", "resources"),
            Map.entry("R-04", "selectGroup"), Map.entry("R-05", "actionGuard"), Map.entry("R-06", "closure"),
            Map.entry("R-07", "reservationReceipt"), Map.entry("R-08", "notification"), Map.entry("R-10", "audit"),
            Map.entry("R-11", "freshness"), Map.entry("R-12", "queueHead"), Map.entry("R-13", "preemption"),
            Map.entry("R-14", "health"), Map.entry("R-15", "restore"));
    private static final Map<String, String> STAGES = Map.ofEntries(
            Map.entry("R-01", "ADMISSION"), Map.entry("R-11", "ADMISSION"),
            Map.entry("R-02", "PLANNING"), Map.entry("R-03", "PLANNING"), Map.entry("R-04", "PLANNING"), Map.entry("R-14", "PLANNING"),
            Map.entry("R-07", "SUBMISSION"), Map.entry("R-12", "SUBMISSION"), Map.entry("R-13", "SUBMISSION"),
            Map.entry("R-05", "EXECUTION"), Map.entry("R-06", "CLOSURE"), Map.entry("R-15", "CLOSURE"),
            Map.entry("R-08", "GLOBAL"), Map.entry("R-10", "GLOBAL"));
    public record Binding(String ruleCode, Rule rule, boolean checkAvailable, String implementation, String message) {}
    private final RuleMapper mapper;
    private final ObjectMapper json;
    private final ComputeRuleEvaluator evaluator = new ComputeRuleEvaluator();

    public ComputeRuleService(RuleMapper mapper, ObjectMapper json) { this.mapper = mapper; this.json = json; }

    public List<Binding> catalog() {
        Map<String, Rule> rules = new HashMap<>();
        mapper.selectList(new LambdaQueryWrapper<Rule>().in(Rule::getRuleCode, ComputeRuleEvaluator.CODES))
                .forEach(rule -> rules.put(rule.getRuleCode(), rule));
        return ComputeRuleEvaluator.CODES.stream().map(code -> {
            Rule rule = rules.get(code);
            try {
                validate(rule);
                return new Binding(code, rule, true, "ComputeRuleEvaluator." + HANDLERS.get(code), "已接入确定性检查；仅演练，不形成运行决策");
            } catch (BizException e) {
                return new Binding(code, rule, false, "ComputeRuleEvaluator." + HANDLERS.get(code), e.getMessage());
            }
        }).toList();
    }

    public ComputeRuleEvaluator.Result check(String code, Integer expectedVersion, JsonNode facts) {
        if (!HANDLERS.containsKey(code)) throw new BizException("不支持的算力规则（R-09 配额已废止）: " + code);
        Rule rule = mapper.selectOne(new LambdaQueryWrapper<Rule>().eq(Rule::getRuleCode, code));
        JsonNode definition = validate(rule);
        if (expectedVersion == null || !expectedVersion.equals(rule.getVersion())) throw new BizException("规则版本已变化，请刷新后重新演练");
        if (facts == null || !facts.isObject() || facts.toString().length() > 65536) throw new BizException("演练事实必须是 JSON 对象，且不超过 64K 字符");
        return evaluator.evaluate(rule, definition, facts);
    }

    /** Validates fixed handler binding; expression text is never executed as SQL, SpEL or script. */
    public JsonNode validate(Rule rule) {
        if (rule == null) throw new BizException("规则定义缺失，请检查算力迁移或人工维护记录");
        if (!"COMPUTE".equals(rule.getEngine()) || !HANDLERS.containsKey(rule.getRuleCode())
                || rule.getConceptCode() == null || !rule.getConceptCode().startsWith("COMPUTE_")
                || rule.getVersion() == null || rule.getVersion() < 1
                || rule.getName() == null || rule.getName().isBlank() || rule.getOwner() == null || rule.getOwner().isBlank())
            throw new BizException("算力规则编号、引擎、概念、版本、名称或负责人不完整");
        try {
            JsonNode definition = json.readTree(rule.getExprJson() == null ? "" : rule.getExprJson());
            if (definition == null || !definition.isObject()
                    || !HANDLERS.get(rule.getRuleCode()).equals(definition.path("handler").asText())
                    || !ComputeRuleEvaluator.VERSION.equals(definition.path("evaluatorVersion").asText())
                    || !STAGES.get(rule.getRuleCode()).equals(definition.path("stage").asText()))
                throw new BizException("规则处理函数、实现版本或阶段不匹配，不能执行未注册的表达式");
            for (String field : List.of("inputs", "outputs", "failureReasons", "workflowSteps")) {
                JsonNode list = definition.path(field);
                if (!list.isArray() || list.isEmpty()) throw new BizException("算力规则缺少 " + field);
                for (JsonNode item : list) if (!item.isTextual() || item.asText().isBlank()) throw new BizException(field + " 必须由非空字符串组成");
            }
            String expectedKind = switch (rule.getRuleCode()) {
                case "R-04", "R-12" -> "PLANNING";
                case "R-05", "R-06", "R-08" -> "ACTION";
                case "R-07" -> "CONSTRAINT";
                default -> "VALIDATION";
            };
            if (!expectedKind.equals(definition.path("kind").asText())) throw new BizException("规则类型与处理函数不匹配");
            if (!definition.path("sampleFacts").isObject() || definition.path("failureRoute").asText().isBlank())
                throw new BizException("算力规则缺少演练样例或失败去向");
            if ("R-11".equals(rule.getRuleCode())) {
                JsonNode age = definition.path("parameters").path("maxAgeSeconds");
                if (!age.isIntegralNumber() || !age.canConvertToLong() || age.longValue() <= 0) throw new BizException("R-11 必须配置正整数 maxAgeSeconds");
            }
            return definition;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException("算力规则 JSON 无法解析");
        }
    }
}
