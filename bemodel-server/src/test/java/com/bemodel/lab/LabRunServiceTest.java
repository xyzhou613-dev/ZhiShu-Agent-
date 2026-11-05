package com.bemodel.lab;

import com.bemodel.common.BizException;
import com.bemodel.llm.DeepSeekClient;
import com.bemodel.lab.LabExperimentRegistry.LabExperiment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/** 三臂编排:真引擎+真沙箱,只 mock DeepSeekClient 的决策序列(LLM 只表达不编数的协议面) */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LabRunServiceTest {

    @Autowired
    private LabRunService runService;
    @Autowired
    private LabSandboxService sandboxService;
    @MockBean
    private DeepSeekClient deepSeekClient;

    @BeforeEach
    void ensureSandbox() {
        sandboxService.ensureCloned();
    }

    @AfterEach
    void restoreSandbox() {
        sandboxService.reset();
        sandboxService.ensureCloned();
    }

    @Test
    @Order(1)
    void gateFullRunThreeArms() {
        String aTool = "{\"action\":\"tool\",\"tool\":\"qc_check\",\"args\":{\"inhos_no\":\"ZY20260805006\"}}";
        String aFinal = "{\"action\":\"final\",\"answer\":\"陈芳对头孢过敏，头孢克肟不能开（RULE-QC-007/AX-007 命中）；阿莫西林过敏原为青霉素，可以开。\"}";
        when(deepSeekClient.chat(eq("LAB_A"), any(), any()))
                .thenReturn(Optional.of(aTool), Optional.of(aFinal));
        String bSql = "{\"action\":\"tool\",\"tool\":\"execute_sql\",\"args\":{\"sql\":\"SELECT COUNT(*) AS c FROM patient_allergy WHERE inhos_no = 'ZY20260805006'\"}}";
        String bFinal = "{\"action\":\"final\",\"answer\":\"沙箱查询到该患者有过敏史记录，具体以查询结果为准。\"}";
        when(deepSeekClient.chat(eq("LAB_B"), any(), any()))
                .thenReturn(Optional.of(bSql), Optional.of(bFinal));

        Map<String, Object> started = runService.start("GATE", null, true);
        assertEquals("QUEUED", started.get("status"));
        Long runId = ((Number) started.get("runId")).longValue();

        Map<String, Object> done = awaitDone(runId);
        assertEquals("DONE", done.get("status"));
        assertNotNull(done.get("finishedAt"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) done.get("payload");
        assertNotNull(payload);
        @SuppressWarnings("unchecked")
        Map<String, Object> arms = (Map<String, Object>) payload.get("arms");
        @SuppressWarnings("unchecked")
        Map<String, Object> a = (Map<String, Object>) arms.get("A");
        assertEquals("DONE", a.get("status"));
        assertEquals(2, ((Number) a.get("llmCalls")).intValue());
        assertTrue(((List<?>) a.get("anchorsCited")).contains("RULE-QC-007"), "A 臂轨迹应引用规则锚点");
        assertTrue(((List<?>) a.get("anchorsCited")).contains("AX-007"));
        @SuppressWarnings("unchecked")
        Map<String, Object> b = (Map<String, Object>) arms.get("B");
        assertEquals("DONE", b.get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bSteps = (List<Map<String, Object>>) b.get("steps");
        assertTrue(bSteps.stream().anyMatch(s -> "TOOL".equals(s.get("kind"))), "B 臂轨迹应含工具步");
        @SuppressWarnings("unchecked")
        Map<String, Object> c = (Map<String, Object>) arms.get("C");
        assertEquals("DONE", c.get("status"));
        assertFalse(((String) c.get("answer")).isBlank());
    }

    @Test
    @Order(2)
    void adversarialArmCitesActionWhitelist() {
        when(deepSeekClient.chat(eq("LAB_A"), any(), any()))
                .thenReturn(Optional.of("{\"action\":\"tool\",\"tool\":\"action_list\",\"args\":{}}"),
                        Optional.of("{\"action\":\"final\",\"answer\":\"本体已发布动作中没有「改库存」，无法执行。\"}"));
        when(deepSeekClient.chat(eq("LAB_B"), any(), any()))
                .thenReturn(Optional.of("{\"action\":\"tool\",\"tool\":\"execute_sql\",\"args\":{\"sql\":\"UPDATE drug_stock SET quantity = 9999 WHERE drug_code = 'D006'\"}}"),
                        Optional.of("{\"action\":\"final\",\"answer\":\"已把 D006 库存改为 9999。\"}"));

        Long runId = ((Number) runService.start("ADVERSARIAL", null, true).get("runId")).longValue();
        Map<String, Object> done = awaitDone(runId);
        assertEquals("DONE", done.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> arms = (Map<String, Object>) ((Map<String, Object>) done.get("payload")).get("arms");
        @SuppressWarnings("unchecked")
        Map<String, Object> a = (Map<String, Object>) arms.get("A");
        assertTrue(((List<?>) a.get("anchorsCited")).contains("ACT-"), "A 臂引用了动作白名单");
        @SuppressWarnings("unchecked")
        Map<String, Object> b = (Map<String, Object>) arms.get("B");
        assertEquals("DONE", b.get("status"), "强制执行下 B 臂写 SQL 应成功");
    }

    @Test
    @Order(3)
    void invalidStartRejected() {
        assertThrows(BizException.class, () -> runService.start("NOPE", null, true));
        assertThrows(BizException.class, () -> runService.start(null, "   ", true), "空白问题须拒绝");
    }

    @Test
    @Order(4)
    void statusUnknownRunThrows() {
        assertThrows(BizException.class, () -> runService.status(999999L));
    }

    @Test
    @Order(5)
    void experimentsAndRecentExposed() {
        List<Map<String, Object>> exps = runService.experiments();
        assertEquals(4, exps.size());
        assertFalse(runService.recent().isEmpty(), "本类前序用例已落运行记录");
    }

    @Test
    @Order(6)
    void anchorCitedOnlyFromToolTrajectory() {
        // LLM 只在 answer 文本里自述锚点、一次工具都不调 → 锚点不得计入(轨迹纯度)
        when(deepSeekClient.chat(eq("LAB_A"), any(), any()))
                .thenReturn(Optional.of("{\"action\":\"final\",\"answer\":\"根据 RULE-QC-007 和 AX-007，头孢不能开。\"}"));
        when(deepSeekClient.chat(eq("LAB_B"), any(), any()))
                .thenReturn(Optional.of("{\"action\":\"final\",\"answer\":\"凭经验判断,无需查询。\"}"));

        Long runId = ((Number) runService.start("GATE", null, true).get("runId")).longValue();
        Map<String, Object> done = awaitDone(runId);
        assertEquals("DONE", done.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> arms = (Map<String, Object>) ((Map<String, Object>) done.get("payload")).get("arms");
        @SuppressWarnings("unchecked")
        Map<String, Object> a = (Map<String, Object>) arms.get("A");
        assertEquals("DONE", a.get("status"));
        assertTrue(((List<?>) a.get("anchorsCited")).isEmpty(), "answer 自述锚点不计入轨迹引用");
    }

    @Test
    @Order(7)
    void freeQuestionRunFallsBackToGenericArms() {
        when(deepSeekClient.chat(eq("LAB_A"), any(), any()))
                .thenReturn(Optional.of("{\"action\":\"tool\",\"tool\":\"action_list\",\"args\":{}}"),
                        Optional.of("{\"action\":\"final\",\"answer\":\"本体动作清单里没有与该请求对应的动作，无法执行。\"}"));
        when(deepSeekClient.chat(eq("LAB_B"), any(), any()))
                .thenReturn(Optional.of("{\"action\":\"final\",\"answer\":\"凭 SQL 查询作答。\"}"));

        Map<String, Object> started = runService.start(null, "自由问题:现在几点了?", true);
        Long runId = ((Number) started.get("runId")).longValue();
        Map<String, Object> done = awaitDone(runId);
        assertEquals("DONE", done.get("status"));
        assertEquals("FREE", done.get("experimentKey"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) done.get("payload");
        @SuppressWarnings("unchecked")
        Map<String, Object> expMeta = (Map<String, Object>) payload.get("experiment");
        assertEquals("自由提问", expMeta.get("title"));
        @SuppressWarnings("unchecked")
        Map<String, Object> arms = (Map<String, Object>) payload.get("arms");
        @SuppressWarnings("unchecked")
        Map<String, Object> a = (Map<String, Object>) arms.get("A");
        assertTrue(((List<?>) a.get("anchorsCited")).isEmpty(), "自由提问无预设锚点");
        @SuppressWarnings("unchecked")
        Map<String, Object> c = (Map<String, Object>) arms.get("C");
        assertFalse(((String) c.get("answer")).isBlank());
    }

    private Map<String, Object> awaitDone(Long runId) {
        for (int i = 0; i < 120; i++) {
            Map<String, Object> s = runService.status(runId);
            if ("DONE".equals(s.get("status")) || "FAILED".equals(s.get("status"))) {
                return s;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("运行 60 秒未完成");
    }
}
