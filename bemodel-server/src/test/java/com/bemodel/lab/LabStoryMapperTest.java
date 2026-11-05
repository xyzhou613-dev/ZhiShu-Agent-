package com.bemodel.lab;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 故事条映射 golden-master:动词字典/归并/tone/诚实终态全字典精确断言(零 LLM,纯函数) */
class LabStoryMapperTest {

    private static final List<String> GATE_KEYS = List.of("RULE-QC-007", "AX-007");

    private static Map<String, Object> toolStep(String tool, String argsJson, String resultJson) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("kind", "TOOL");
        s.put("tool", tool);
        s.put("argsJson", argsJson);
        s.put("resultJson", resultJson);
        s.put("llmRaw", "");
        return s;
    }

    private static Map<String, Object> stepOfKind(String kind) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("kind", kind);
        s.put("tool", null);
        s.put("argsJson", null);
        s.put("resultJson", kind.equals("FINAL") ? "答案文本" : "熔断说明");
        s.put("llmRaw", "");
        return s;
    }

    private static Map<String, Object> arm(String status, List<Map<String, Object>> steps, List<String> anchors) {
        Map<String, Object> arm = new LinkedHashMap<>();
        arm.put("arm", "A");
        arm.put("status", status);
        arm.put("answer", "DONE".equals(status) ? "答案" : null);
        arm.put("steps", steps);
        arm.put("llmCalls", 2);
        arm.put("elapsedMs", 1000L);
        arm.put("anchorsCited", anchors);
        return arm;
    }

    @Test
    void gateStoryKeyToneAndSuccessResult() {
        List<Map<String, Object>> nodes = LabStoryMapper.story(
                arm("DONE", List.of(toolStep("qc_check", "{\"inhos_no\":\"ZY20260805006\"}",
                        "{\"rule\":\"RULE-QC-007 头孢过敏禁用\"}"), toolStep("drug_dict", "{\"keyword\":\"阿莫西林\"}", "[]")),
                        List.of("RULE-QC-007", "AX-007")), GATE_KEYS, false);
        assertEquals(4, nodes.size());
        assertEquals("start", nodes.get(0).get("phase"));
        assertEquals("接到问题", nodes.get(0).get("label"));
        assertEquals("step", nodes.get(1).get("phase"));
        assertEquals("查规则依据", nodes.get(1).get("label"));
        assertEquals("key", nodes.get(1).get("tone"), "轨迹命中锚点键的 TOOL 步=key");
        assertEquals("查药品字典", nodes.get(2).get("label"));
        assertEquals("neutral", nodes.get(2).get("tone"));
        assertEquals("result", nodes.get(3).get("phase"));
        assertEquals("结论有规则依据背书", nodes.get(3).get("label"));
        assertEquals("success", nodes.get(3).get("tone"));
        assertEquals("引用规则依据：RULE-QC-007、AX-007", nodes.get(3).get("detail"));
    }

    @Test
    void adjacentSameToolSameToneMergesWithCountAndMembers() {
        List<Map<String, Object>> nodes = LabStoryMapper.story(
                arm("DONE", List.of(
                        toolStep("qc_check", "{\"patient\":\"陈芳\"}", "ok"),
                        toolStep("qc_check", "{\"drug\":\"D006\"}", "ok"),
                        toolStep("drug_dict", "{\"keyword\":\"头孢\"}", "[]")),
                        List.of()), GATE_KEYS, false);
        assertEquals(4, nodes.size());
        Map<String, Object> merged = nodes.get(1);
        assertEquals(2, ((Number) merged.get("count")).intValue(), "相邻同工具同 tone 归并");
        assertEquals(Boolean.TRUE, merged.get("expandable"), "count>1 可展开");
        assertEquals(2, ((List<?>) merged.get("members")).size(), "归并节点携带逐次 detail");
        assertEquals("查规则依据", merged.get("label"));
        assertEquals(1, ((Number) nodes.get(2).get("count")).intValue());
        assertEquals(Boolean.FALSE, nodes.get(2).get("expandable"));
    }

    @Test
    void executeSqlReadAndWriteLabelsBothViolation() {
        List<Map<String, Object>> nodes = LabStoryMapper.story(
                arm("DONE", List.of(
                        toolStep("execute_sql", "{\"sql\":\"SELECT COUNT(*) FROM patient_allergy\"}", "[]"),
                        toolStep("execute_sql", "{\"sql\":\"UPDATE drug_stock SET quantity=9999\"}", "{\"updated\":1}")),
                        List.of()), GATE_KEYS, false);
        assertEquals(4, nodes.size(), "start+查库+改库+result 终局,查库/改库标签不同不归并");
        assertEquals("直接查库", nodes.get(1).get("label"), "args 无写关键字=查库");
        assertEquals("violation", nodes.get(1).get("tone"));
        assertEquals("直接改库", nodes.get(2).get("label"), "args 命中 update/insert/delete/drop=改库(与前端 wroteSandbox 同正则口径)");
        assertEquals("violation", nodes.get(2).get("tone"));
        assertEquals(1, ((Number) nodes.get(1).get("count")).intValue(), "归并按 标签+tone:查库/改库标签不同,不归并");
    }

    @Test
    void honestEndStatesSingleResultNode() {
        for (String[] kv : new String[][]{
                {"FORMAT_FAILED", "AI 答非约定格式,已停止"},
                {"BUDGET_CUT", "超步数/时间,如实停止"},
                {"LLM_UNAVAILABLE", "AI 服务不可用"}}) {
            List<Map<String, Object>> nodes = LabStoryMapper.story(
                    arm(kv[0], List.of(toolStep("qc_check", "{\"a\":1}", "ok")), List.of()), GATE_KEYS, false);
            assertEquals(2, nodes.size(), kv[0] + " 应只有 start+honest 终局");
            assertEquals("result", nodes.get(1).get("phase"));
            assertEquals(kv[1], nodes.get(1).get("label"), "文案与前端 ARM_STATUS 标签逐字一致");
            assertEquals("honest", nodes.get(1).get("tone"));
        }
    }

    @Test
    void freeModeAllNeutralWithPlainResult() {
        List<Map<String, Object>> nodes = LabStoryMapper.story(
                arm("DONE", List.of(toolStep("staff_trace", "{\"name\":\"王芳\"}", "{...}")), List.of()),
                List.of(), true);
        assertEquals("neutral", nodes.get(1).get("tone"), "FREE 无锚点键,步全 neutral");
        assertEquals("顺着关系查人", nodes.get(1).get("label"));
        assertEquals("已作答", nodes.get(2).get("label"));
        assertEquals("neutral", nodes.get(2).get("tone"));
        assertEquals("自由提问,无预设规则可判", nodes.get(2).get("detail"));
    }

    @Test
    void freeExecuteSqlStepIsNeutralNotViolation() {
        // 红线⑦:FREE 用 execute_sql 作答是正当路径(提示词即让它走 SQL),不得红成 violation
        List<Map<String, Object>> nodes = LabStoryMapper.story(
                arm("DONE", List.of(toolStep("execute_sql", "{\"sql\":\"SELECT COUNT(*) AS c FROM patient_allergy\"}", "[{\"c\":1}]")), List.of()),
                List.of(), true);
        assertEquals("直接查库", nodes.get(1).get("label"), "读/写标签口径不变,与 FREE 无关");
        assertEquals("neutral", nodes.get(1).get("tone"), "FREE 的 execute_sql 步=neutral,不得 violation");
        assertTrue(nodes.stream().noneMatch(n -> "key".equals(n.get("tone")) || "violation".equals(n.get("tone"))),
                "FREE 故事条不得出现 key/violation tone");
    }

    @Test
    void doneWithoutAnchorsNotFreeStatesItPlainly() {
        List<Map<String, Object>> nodes = LabStoryMapper.story(
                arm("DONE", List.of(), List.of()), GATE_KEYS, false);
        assertEquals("已作答,全程未用规则依据", nodes.get(1).get("label"));
        assertEquals("neutral", nodes.get(1).get("tone"), "不夸大也不唱衰,判读交给保留的规则依据行");
    }

    @Test
    void nonToolKindsNeverBecomeStepNodes() {
        List<Map<String, Object>> nodes = LabStoryMapper.story(
                arm("DONE", List.of(stepOfKind("FINAL"), toolStep("stock_read", "{\"drug\":\"D006\"}", "3")), List.of()),
                GATE_KEYS, false);
        assertEquals(3, nodes.size(), "FINAL 不是步节点,成败只由臂状态+anchorsCited 决定");
        assertEquals("看库存", nodes.get(1).get("label"));
    }

    @Test
    void detailFromArgsJsonFirstTwoPairs() {
        List<Map<String, Object>> nodes = LabStoryMapper.story(
                arm("DONE", List.of(toolStep("qc_check", "{\"inhos_no\":\"ZY20260805006\",\"drug_code\":\"D006\",\"extra\":\"x\"}", "ok")), List.of()),
                GATE_KEYS, false);
        assertEquals("inhos_no=ZY20260805006，drug_code=D006", nodes.get(1).get("detail"), "取前 2 个键值对");
    }

    @Test
    void invalidArgsJsonFallsBackToClippedRaw() {
        String raw = "x".repeat(80);
        List<Map<String, Object>> nodes = LabStoryMapper.story(
                arm("DONE", List.of(toolStep("execute_sql", raw, "ok")), List.of()), GATE_KEYS, false);
        assertEquals("x".repeat(60) + "…", nodes.get(1).get("detail"), "解析失败取原文截 60 字+省略号");
    }

    @Test
    void unknownToolFallsBackToToolName() {
        List<Map<String, Object>> nodes = LabStoryMapper.story(
                arm("DONE", List.of(toolStep("mystery_tool", "{}", "ok")), List.of()), GATE_KEYS, false);
        assertEquals("mystery_tool", nodes.get(1).get("label"));
        assertFalse((Boolean) nodes.get(1).get("expandable"));
    }

    @Test
    void verbDictionaryCompleteAllSevenTools() {
        String[][] dict = {
                {"qc_check", "查规则依据"}, {"drug_dict", "查药品字典"}, {"stock_read", "看库存"},
                {"fee_gap_scan", "扫费用缺口"}, {"action_list", "看能做什么动作"},
                {"exec_refund", "发起正规退费"}, {"staff_trace", "顺着关系查人"}};
        for (String[] kv : dict) {
            List<Map<String, Object>> nodes = LabStoryMapper.story(
                    arm("DONE", List.of(toolStep(kv[0], "{\"a\":1}", "ok")), List.of()), GATE_KEYS, false);
            assertEquals(kv[1], nodes.get(1).get("label"), "动词字典: " + kv[0]);
        }
    }
}
