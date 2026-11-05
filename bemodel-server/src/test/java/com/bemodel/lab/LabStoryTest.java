package com.bemodel.lab;

import com.bemodel.llm.DeepSeekClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** 故事条端到端:真三臂编排后 status() 应带 storyA/storyB;诚实终态/锚点 tone/归并字段逐点验收 */
@SpringBootTest
class LabStoryTest {

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

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> story(Map<String, Object> status, String key) {
        return (List<Map<String, Object>>) status.get(key);
    }

    @Test
    void gateStoryHasVerbNodeKeyToneAndSuccessEnd() {
        // 决策序列与 LabRunServiceTest.gateFullRunThreeArms 同源:qc_check 一步+final
        when(deepSeekClient.chat(eq("LAB_A"), any(), any()))
                .thenReturn(Optional.of("{\"action\":\"tool\",\"tool\":\"qc_check\",\"args\":{\"inhos_no\":\"ZY20260805006\"}}"),
                        Optional.of("{\"action\":\"final\",\"answer\":\"头孢克肟不能开（RULE-QC-007）；阿莫西林可以开。\"}"));
        when(deepSeekClient.chat(eq("LAB_B"), any(), any()))
                .thenReturn(Optional.of("{\"action\":\"tool\",\"tool\":\"execute_sql\",\"args\":{\"sql\":\"SELECT COUNT(*) AS c FROM patient_allergy WHERE inhos_no = 'ZY20260805006'\"}}"),
                        Optional.of("{\"action\":\"final\",\"answer\":\"沙箱查询到该患者有过敏史记录。\"}"));

        Long runId = ((Number) runService.start("GATE", null, true).get("runId")).longValue();
        Map<String, Object> done = awaitDone(runId);
        assertEquals("DONE", done.get("status"));

        List<Map<String, Object>> a = story(done, "storyA");
        assertNotNull(a, "DONE 后应有 storyA");
        assertEquals("接到问题", a.get(0).get("label"));
        assertTrue(a.stream().anyMatch(n -> "查规则依据".equals(n.get("label")) && "key".equals(n.get("tone"))),
                "qc_check 真实工具返回含 RULE-QC-007,该步应为 key tone");
        Map<String, Object> aEnd = a.get(a.size() - 1);
        assertEquals("result", aEnd.get("phase"));
        assertEquals("success", aEnd.get("tone"));
        assertTrue(String.valueOf(aEnd.get("detail")).contains("RULE-QC-007"));

        List<Map<String, Object>> b = story(done, "storyB");
        assertNotNull(b);
        assertTrue(b.stream().anyMatch(n -> "直接查库".equals(n.get("label")) && "violation".equals(n.get("tone"))),
                "B 臂 SELECT 步=直接查库+violation");
        assertEquals("已作答,全程未用规则依据", b.get(b.size() - 1).get("label"), "B 臂无锚点,如实中性陈述");
    }

    @Test
    void formatFailedStoryIsHonestTwoNodes() {
        // 两次输出都不合协议→FORMAT_FAILED:故事条只有 start+honest 终局,不硬凑
        when(deepSeekClient.chat(eq("LAB_A"), any(), any()))
                .thenReturn(Optional.of("这不是JSON"), Optional.of("还不是JSON"));
        when(deepSeekClient.chat(eq("LAB_B"), any(), any()))
                .thenReturn(Optional.of("同样不合协议"), Optional.of("依然不合协议"));

        Long runId = ((Number) runService.start("GATE", null, true).get("runId")).longValue();
        Map<String, Object> done = awaitDone(runId);
        assertEquals("DONE", done.get("status"), "臂失败不拖垮 run");

        List<Map<String, Object>> a = story(done, "storyA");
        assertEquals(2, a.size());
        assertEquals("honest", a.get(1).get("tone"));
        assertEquals("AI 答非约定格式,已停止", a.get(1).get("label"));
    }

    @Test
    void freeRunStoryAllNeutral() {
        // 自由提问走 FREE_KEY:故事条仍应生成且全 neutral(spec §3 无锚点键→全 neutral),不因注册表无 FREE 条目而缺席
        // A 臂含一步 execute_sql( FREE 的正当作答路径):锁住「FREE 的 SQL 步不得红成 violation」不回退
        when(deepSeekClient.chat(eq("LAB_A"), any(), any()))
                .thenReturn(Optional.of("{\"action\":\"tool\",\"tool\":\"action_list\",\"args\":{}}"),
                        Optional.of("{\"action\":\"tool\",\"tool\":\"execute_sql\",\"args\":{\"sql\":\"SELECT 1\"}}"),
                        Optional.of("{\"action\":\"final\",\"answer\":\"本体已发布动作里没有对应能力,无法执行。\"}"));
        when(deepSeekClient.chat(eq("LAB_B"), any(), any()))
                .thenReturn(Optional.of("{\"action\":\"final\",\"answer\":\"凭 SQL 查询作答。\"}"));

        Long runId = ((Number) runService.start(null, "自由问题:明天会下雨吗?", true).get("runId")).longValue();
        Map<String, Object> done = awaitDone(runId);
        assertEquals("DONE", done.get("status"));
        assertEquals("FREE", done.get("experimentKey"));

        List<Map<String, Object>> a = story(done, "storyA");
        assertNotNull(a, "FREE 运行也应有 storyA");
        assertTrue(a.stream().anyMatch(n -> "直接查库".equals(n.get("label"))),
                "A 臂 execute_sql 步应入故事条(直接查库)");
        assertTrue(a.stream().allMatch(n -> "neutral".equals(n.get("tone"))),
                "FREE 无锚点键,storyA 全 neutral,key/violation 不得出现(含 execute_sql 步)");
        assertEquals("已作答", a.get(a.size() - 1).get("label"));

        List<Map<String, Object>> b = story(done, "storyB");
        assertNotNull(b, "FREE 运行也应有 storyB");
        assertTrue(b.stream().allMatch(n -> "neutral".equals(n.get("tone"))), "storyB 同理全 neutral");
        assertEquals("已作答", b.get(b.size() - 1).get("label"));
    }

    private Map<String, Object> awaitDone(Long runId) {
        for (int i = 0; i < 120; i++) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            Map<String, Object> s = runService.status(runId);
            String st = String.valueOf(s.get("status"));
            if ("DONE".equals(st) || "FAILED".equals(st)) {
                return s;
            }
        }
        throw new IllegalStateException("运行 60s 未结束");
    }
}
