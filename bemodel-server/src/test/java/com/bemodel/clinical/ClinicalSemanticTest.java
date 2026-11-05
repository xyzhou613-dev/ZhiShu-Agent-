package com.bemodel.clinical;

import com.bemodel.modeling.service.AxiomService;
import com.bemodel.ontology.service.TermService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 临床语义底座验证：公理、SNOMED 对接、病案内涵质控（逻辑矛盾）、危重症预警、AI 溯源链。
 */
@SpringBootTest
class ClinicalSemanticTest {

    @Autowired
    private AxiomService axiomService;
    @Autowired
    private TermService termService;
    @Autowired
    private QcService qcService;
    @Autowired
    private AlertService alertService;
    @Autowired
    private com.bemodel.link.service.LinkService linkService;

    private void linkServiceCleanup() {
        linkService.remove(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.bemodel.link.entity.LinkNode>()
                .likeRight(com.bemodel.link.entity.LinkNode::getRefNo, "ALERT-"));
    }

    private String recordIdOf(String inhosNo) {
        return qcService.records(null).stream()
                .filter(r -> inhosNo.equals(r.get("inhos_no")))
                .map(r -> String.valueOf(r.get("record_id")))
                .findFirst().orElseThrow();
    }

    @Test
    void axiomsAndSnomedSeeded() {
        assertEquals(9, axiomService.listAll().size(), "应预置9条公理（V13新增处方审核三件套 AX-007/008/009）");
        assertTrue(termService.listAll(null).stream().anyMatch(t ->
                "SNOMED CT".equals(t.getCodeSystem()) && "38341003".equals(t.getStandardCode())),
                "术语库应对接 SNOMED CT（高血压 38341003）");
    }

    @Test
    void qcShouldCatchPlantedContradictions() {
        // 李红梅：诊断2型糖尿病但无空腹血糖检验 → RULE-QC-001
        Map<String, Object> r1 = qcService.check(recordIdOf("ZY20260812002"));
        assertEquals(Boolean.FALSE, r1.get("pass"));
        assertTrue(r1.get("findings").toString().contains("RULE-QC-001"));

        // 郑国庆：阑尾切除术缺凝血四项 → RULE-QC-002 + 公理AX-002
        Map<String, Object> r2 = qcService.check(recordIdOf("ZY20260802011"));
        assertEquals(Boolean.FALSE, r2.get("pass"));
        assertTrue(r2.get("findings").toString().contains("RULE-QC-002"));
        assertTrue(r2.get("trace").toString().contains("AX-002"));

        // 杨光：C反应蛋白异常无处置 → RULE-QC-003
        Map<String, Object> r3 = qcService.check(recordIdOf("ZY20260822007"));
        assertEquals(Boolean.FALSE, r3.get("pass"));
        assertTrue(r3.get("findings").toString().contains("RULE-QC-003"));

        // 吴丽丽：并发症上消化道出血无处置 → RULE-QC-004
        Map<String, Object> r4 = qcService.check(recordIdOf("ZY20260828010"));
        assertEquals(Boolean.FALSE, r4.get("pass"));
        assertTrue(r4.get("findings").toString().contains("RULE-QC-004"));

        // 孙志强：男性诊断卵巢囊肿 → RULE-QC-005 + 公理AX-003
        Map<String, Object> r5 = qcService.check(recordIdOf("ZY202682000013"));
        assertEquals(Boolean.FALSE, r5.get("pass"));
        assertTrue(r5.get("findings").toString().contains("RULE-QC-005"));

        // 陈芳：对头孢严重过敏但已执行 D006 头孢克肟医嘱 → RULE-QC-007（V13新增，过敏禁忌，公理AX-007）
        // —— 与 SHACL Shape 1（AllergyConstraint）同一违规的平台引擎命中，双引擎互证
        Map<String, Object> r6 = qcService.check(recordIdOf("ZY20260805006"));
        assertEquals(Boolean.FALSE, r6.get("pass"), "过敏禁忌应被命中: " + r6.get("findings"));
        assertTrue(r6.get("findings").toString().contains("RULE-QC-007"));
        assertTrue(r6.get("trace").toString().contains("AX-007"));

        // 冯雪：布洛芬 1.0g tid 超日最大剂量 2.4g → RULE-QC-008（V13新增，剂量上限，公理AX-008）
        // —— 与 SHACL Shape 2（DoseLimitShape）同一违规的平台引擎命中
        Map<String, Object> r7 = qcService.check(recordIdOf("ZY20260728012"));
        assertEquals(Boolean.FALSE, r7.get("pass"), "超量应被命中: " + r7.get("findings"));
        assertTrue(r7.get("findings").toString().contains("RULE-QC-008"));

        // 张建国：冠心病 + 心律失常，医嘱/检验/发药齐备无违规 → 通过（干净对照）
        Map<String, Object> r8 = qcService.check(recordIdOf("ZY20260815001"));
        assertEquals(Boolean.TRUE, r8.get("pass"), "干净病例应通过: " + r8.get("findings"));

        // 溯源链必须含本体版本
        assertTrue(r1.get("trace").toString().contains("ontologyVersion"));
    }

    @Test
    void qcCheckAllShouldMatchPlantedCounts() {
        Map<String, Object> all = qcService.checkAll();
        assertEquals(13, ((Number) all.get("total")).intValue());
        assertEquals(7, ((Number) all.get("fail")).intValue(), "预埋5份矛盾病案 + V13过敏/超量2份（新规则配置发布即命中存量）");
    }

    @Test
    void criticalAlertShouldOnlyFireForUnhandledAbnormal() {
        // 清理历史预警，保证用例幂等
        linkServiceCleanup();
        Map<String, Object> r = alertService.detect();
        assertEquals(2, ((Number) r.get("abnormalReports")).intValue(), "共2份异常报告");
        assertEquals(1, ((Number) r.get("handled")).intValue(), "冯雪已处置，不预警");
        assertEquals(1, ((Number) r.get("createdCount")).intValue(), "杨光未处置，应预警");
        Map<String, Object> again = alertService.detect();
        assertEquals(0, ((Number) again.get("createdCount")).intValue(), "幂等不重复预警");
    }

    @Test
    void qc006ExamRuleIsPurelyConfiguredNotCoded() {
        // RULE-QC-006（检查报告异常须处置）在 V9 中仅作为数据插入，无任何专属代码
        Map<String, Object> yangguang = qcService.check(recordIdOf("ZY20260822007"));
        assertTrue(yangguang.get("findings").toString().contains("RULE-QC-006"),
                "杨光CT示肺结节未处置，应命中配置的 QC-006");
        // 王强 CT 异常但有诊断关键词+手术处置 → 不命中
        Map<String, Object> wangqiang = qcService.check(recordIdOf("ZY20260816003"));
        assertFalse(wangqiang.get("findings").toString().contains("RULE-QC-006"),
                "王强胸腔积液CT异常已有手术处置，不应命中");
    }

    @Test
    void newRuleTakesEffectAtRuntimeWithoutCodeChange() {
        // 运行时新增一条规则：诊断含「冠心病」须有已执行的布洛芬医嘱（张建国没有 → 应命中）
        com.bemodel.modeling.entity.Rule rule = new com.bemodel.modeling.entity.Rule();
        rule.setRuleCode("RULE-TEST-RUNTIME");
        rule.setName("运行时规则验证");
        rule.setConceptCode("DIAGNOSIS");
        rule.setRuleType("校验");
        rule.setSeverity("低");
        rule.setExpression("冠心病须有头孢呋辛（验证用，无临床意义）");
        rule.setEngine("QC");
        rule.setExprJson("{\"type\":\"DIAG_REQUIRES_ITEM\",\"cases\":[{\"diagKeywords\":[\"冠心病\"],\"kind\":\"DRUG\",\"codes\":[\"D011\"],\"requireName\":\"头孢呋辛\"}]}");
        ruleService.create(rule);
        ruleService.transition("RULE-TEST-RUNTIME", "REVIEW");
        ruleService.transition("RULE-TEST-RUNTIME", "PUBLISHED");
        try {
            Map<String, Object> r = qcService.check(recordIdOf("ZY20260815001"));
            assertTrue(r.get("findings").toString().contains("RULE-TEST-RUNTIME"),
                    "新发布规则应即刻生效（免开发）");
        } finally {
            ruleService.remove(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.bemodel.modeling.entity.Rule>()
                    .eq(com.bemodel.modeling.entity.Rule::getRuleCode, "RULE-TEST-RUNTIME"));
        }
        Map<String, Object> after = qcService.check(recordIdOf("ZY20260815001"));
        assertFalse(after.get("findings").toString().contains("RULE-TEST-RUNTIME"), "规则删除后应即刻失效");
    }

    @Autowired
    private com.bemodel.modeling.service.RuleService ruleService;
}
