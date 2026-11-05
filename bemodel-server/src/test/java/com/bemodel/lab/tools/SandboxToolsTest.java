package com.bemodel.lab.tools;

import com.bemodel.datasource.service.DatasourceService;
import com.bemodel.lab.LabSandboxService;
import com.bemodel.lab.engine.LabTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A 臂工具箱:本体能力在沙箱上的表达,四实验装配齐、每个工具真实出数 */
@SpringBootTest
class SandboxToolsTest {

    @Autowired
    private SandboxTools tools;
    @Autowired
    private LabSandboxService sandboxService;
    @Autowired
    private DatasourceService datasourceService;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void ensureSandbox() {
        sandboxService.ensureCloned();
    }

    private LabTool tool(String expKey, String name) {
        return tools.forExperiment(expKey).stream()
                .filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    private Map<String, Object> run(LabTool t, String argsJson) {
        JsonNode args;
        try {
            args = om.readTree(argsJson);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return t.execute(args);
    }

    @Test
    void gateCatalog() {
        assertEquals(List.of("qc_check", "drug_dict"),
                tools.forExperiment("GATE").stream().map(LabTool::name).toList());
    }

    @Test
    void qcCheckHitsAllergyRule() {
        Map<String, Object> out = run(tool("GATE", "qc_check"), "{\"inhos_no\":\"ZY20260805006\"}");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) out.get("findings");
        assertTrue(findings != null && findings.size() >= 1, "陈芳既有医嘱应命中过敏禁忌");
        assertTrue(String.valueOf(findings.get(0).get("evidence")).contains("头孢"));
        @SuppressWarnings("unchecked")
        List<String> rulesCited = (List<String>) out.get("rulesCited");
        assertTrue(rulesCited.contains("RULE-QC-007"));
        assertTrue(String.valueOf(out.get("scope")).contains("记录级"));
    }

    @Test
    void drugDictShowsAllergen() {
        Map<String, Object> out = run(tool("GATE", "drug_dict"), "{\"drug_code\":\"D006\"}");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("rows");
        assertEquals("头孢", rows.get(0).get("allergen"));
    }

    @Test
    void actionListContainsRefund() {
        Map<String, Object> out = run(tool("ADVERSARIAL", "action_list"), "{}");
        assertEquals("bm_action 已发布动作(白名单)", out.get("basis"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("rows");
        assertTrue(rows.stream().anyMatch(r -> "ACT-REFUND".equals(r.get("actionCode"))));
    }

    @Test
    void stockReadReturnsRows() {
        Map<String, Object> out = run(tool("ADVERSARIAL", "stock_read"), "{}");
        assertTrue(((Number) out.get("count")).intValue() >= 1);
    }

    @Test
    void feeGapScanFindsSeedGaps() {
        Map<String, Object> out = run(tool("REFUND", "fee_gap_scan"), "{}");
        assertTrue(((Number) out.get("count")).intValue() >= 1,
                "种子必须预埋「撤销后仍收费」缺口,否则 E3 剧本失配——升级讨论,不要改断言");
    }

    @Test
    void execRefundCreatesAppliesThenReset() {
        try {
            Map<String, Object> out = run(tool("REFUND", "exec_refund"), "{}");
            assertEquals("ACT-REFUND", out.get("action"));
            assertTrue(((Number) out.get("refundCount")).intValue() >= 1);
            @SuppressWarnings("unchecked")
            List<String> refundIds = (List<String>) out.get("refundIds");
            assertFalse(refundIds.isEmpty());
            // 落库核实:退费申请真在沙箱 refund_apply 里
            Long cnt = datasourceService.jdbc(LabSandboxService.DS_LAB).queryForObject(
                    "SELECT COUNT(*) FROM refund_apply WHERE refund_id = ?", Long.class, refundIds.get(0));
            assertEquals(1L, cnt);
        } finally {
            sandboxService.reset();
        }
    }

    @Test
    void staffTraceWangFang() {
        Map<String, Object> out = run(tool("TRAVERSE", "staff_trace"), "{\"name\":\"护士 王芳\"}");
        assertEquals(true, out.get("found"));
        @SuppressWarnings("unchecked")
        Map<String, Object> staff = (Map<String, Object>) out.get("staff");
        assertEquals("S007", staff.get("staff_id"));
        @SuppressWarnings("unchecked")
        Map<String, Object> dept = (Map<String, Object>) out.get("dept");
        assertEquals("护理部", dept.get("dept_name"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> colleagues = (List<Map<String, Object>>) out.get("colleagues");
        assertTrue(colleagues.stream().anyMatch(c -> "李晓".equals(c.get("staff_name"))));
        @SuppressWarnings("unchecked")
        Map<String, Object> footprint = (Map<String, Object>) out.get("footprint");
        assertEquals(4, footprint.size(), "足迹只统计已克隆表:开立/在管/审核/发药");
    }
}
