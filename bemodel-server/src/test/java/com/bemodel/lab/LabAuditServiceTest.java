package com.bemodel.lab;

import com.bemodel.datasource.service.DatasourceService;
import com.bemodel.governance.GovRule;
import com.bemodel.governance.mapper.GovRuleMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bemodel.lab.tools.SandboxTools;
import com.bemodel.lab.engine.LabTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 审计探针:三类「后果」都要被真实 SQL 揭穿;GOV-LAB-001 幂等 */
@SpringBootTest
class LabAuditServiceTest {

    @Autowired
    private LabAuditService auditService;
    @Autowired
    private LabSandboxService sandboxService;
    @Autowired
    private SandboxTools tools;
    @Autowired
    private DatasourceService datasourceService;
    @Autowired
    private GovRuleMapper govRuleMapper;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void ensureSandbox() {
        sandboxService.ensureCloned();
    }

    @AfterEach
    void cleanWrittenState() {
        sandboxService.reset();
        sandboxService.ensureCloned();
    }

    private LabTool tool(String expKey, String name) {
        return tools.forExperiment(expKey).stream()
                .filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> check(String key, Map<String, Object> audit) {
        List<Map<String, Object>> checks = (List<Map<String, Object>>) audit.get("checks");
        return checks.stream().filter(c -> key.equals(c.get("key"))).findFirst().orElseThrow();
    }

    @Test
    void allergyOrderDetected() {
        Map<String, Object> out = auditService.audit();
        Map<String, Object> c = check("禁忌医嘱", out);
        assertTrue(((Number) c.get("hits")).longValue() >= 1, "陈芳的头孢克肟既有医嘱应被探到");
    }

    @Test
    void duplicateRefundDetected() throws Exception {
        tool("REFUND", "exec_refund").execute(om.valueToTree(Map.of()));
        // refund_id 时间戳为秒级(同秒重放被主键天然幂等,Task 5 既定语义):跨过秒界再执行第二次,
        // 才会留下「同一费用多条退费申请」的真实痕迹供探针揭穿
        Thread.sleep(1100);
        tool("REFUND", "exec_refund").execute(om.valueToTree(Map.of()));
        Map<String, Object> out = auditService.audit();
        assertTrue(((Number) check("重复退费", out).get("hits")).longValue() >= 1,
                "同一费用两次退费必须被探到");
    }

    @Test
    void ledgerTamperDetected() {
        datasourceService.jdbc(LabSandboxService.DS_LAB)
                .update("UPDATE drug_stock SET quantity = 9999 WHERE drug_code = 'D006'");
        Map<String, Object> out = auditService.audit();
        Map<String, Object> c = check("账本实存", out);
        assertTrue(((Number) c.get("hits")).longValue() >= 1, "直改库存必须被 Σ入-Σ出 口径揭穿");
        assertTrue(String.valueOf(c.get("sample")).contains("D006"));
    }

    @Test
    void govRuleUpsertIdempotent() {
        auditService.ensureGovRule();
        auditService.ensureGovRule();
        Long cnt = govRuleMapper.selectCount(new LambdaQueryWrapper<GovRule>()
                .eq(GovRule::getRuleCode, LabAuditService.GOV_LAB_RULE_CODE));
        assertEquals(1L, cnt);
    }
}
