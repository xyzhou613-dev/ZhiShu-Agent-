package com.bemodel.lab.engine;

import com.bemodel.datasource.service.DatasourceService;
import com.bemodel.lab.LabSandboxService;
import com.bemodel.llm.DeepSeekClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 工具循环(Plan 1)锚点测试:tool→final 正常链、格式纠错一次回喂、再犯 FORMAT_FAILED、
 * 未知工具观察项回喂、步数熔断 BUDGET_CUT、LLM 不可用诚实降级。
 * LLM 全脚本化(@MockBean);测试工具读真实沙箱库(顺带打通 沙箱→DS_LAB→工具 全链)。
 */
@SpringBootTest
class ToolLoopRunnerTest {

    @Autowired
    private ToolLoopRunner runner;
    @Autowired
    private LabSandboxService sandbox;
    @Autowired
    private DatasourceService datasourceService;
    @MockBean
    private DeepSeekClient llm;

    @BeforeEach
    void setUp() {
        when(llm.enabled()).thenReturn(true);
        sandbox.ensureCloned();
    }

    /** 真实工具:数 DS_LAB 沙箱 drug_stock 行数(演示库种子>0,是确定事实) */
    static class StockCountTool implements LabTool {
        private final DatasourceService ds;
        StockCountTool(DatasourceService ds) { this.ds = ds; }
        public String name() { return "stock_count"; }
        public String description() { return "统计药品库存行数"; }
        public String argsSchema() { return "{}"; }
        public Map<String, Object> execute(JsonNode args) {
            return Map.of("count", ds.jdbc(LabSandboxService.DS_LAB)
                    .queryForObject("SELECT COUNT(*) FROM drug_stock", Long.class));
        }
    }

    @Test
    void tool_then_final_done() {
        when(llm.chat(anyString(), anyString(), anyString())).thenReturn(
                java.util.Optional.of("{\"action\":\"tool\",\"tool\":\"stock_count\",\"args\":{}}"),
                java.util.Optional.of("{\"action\":\"final\",\"answer\":\"库存查询完成\"}"));
        ToolLoopResult r = runner.run("LAB_A", "你是实验室助手。", "库存还有多少",
                List.of(new StockCountTool(datasourceService)), 12, 90);
        assertEquals(ToolLoopResult.Status.DONE, r.getStatus());
        assertEquals(2, r.getSteps().size());
        assertEquals(ToolStep.TOOL, r.getSteps().get(0).getKind());
        assertEquals("stock_count", r.getSteps().get(0).getTool());
        assertEquals(ToolStep.FINAL, r.getSteps().get(1).getKind());
        assertEquals("库存查询完成", r.getAnswer());
        assertEquals(2, r.getLlmCalls());
    }

    @Test
    void format_repair_once_then_done() {
        when(llm.chat(anyString(), anyString(), anyString())).thenReturn(
                java.util.Optional.of("我觉得应该先查一下库存。"),
                java.util.Optional.of("{\"action\":\"final\",\"answer\":\"好\"}"));
        ToolLoopResult r = runner.run("LAB_A", "你是实验室助手。", "问题",
                List.of(new StockCountTool(datasourceService)), 12, 90);
        assertEquals(ToolLoopResult.Status.DONE, r.getStatus());
        assertEquals(ToolStep.FORMAT_ERROR, r.getSteps().get(0).getKind());
        assertEquals(2, r.getLlmCalls());
    }

    @Test
    void format_failed_twice_honest_no_answer() {
        when(llm.chat(anyString(), anyString(), anyString())).thenReturn(
                java.util.Optional.of("第一次不合协议"), java.util.Optional.of("第二次还是不合协议"));
        ToolLoopResult r = runner.run("LAB_A", "你是实验室助手。", "问题",
                List.of(new StockCountTool(datasourceService)), 12, 90);
        assertEquals(ToolLoopResult.Status.FORMAT_FAILED, r.getStatus());
        assertNull(r.getAnswer());
        assertEquals(2, r.getLlmCalls());
    }

    @Test
    void unknown_tool_becomes_error_observation() {
        when(llm.chat(anyString(), anyString(), anyString())).thenReturn(
                java.util.Optional.of("{\"action\":\"tool\",\"tool\":\"nope\",\"args\":{}}"),
                java.util.Optional.of("{\"action\":\"final\",\"answer\":\"完成\"}"));
        ToolLoopResult r = runner.run("LAB_A", "你是实验室助手。", "问题",
                List.of(new StockCountTool(datasourceService)), 12, 90);
        assertEquals(ToolLoopResult.Status.DONE, r.getStatus());
        assertTrue(r.getSteps().get(0).getResultJson().contains("工具不存在"));
    }

    @Test
    void step_limit_budget_cut() {
        when(llm.chat(anyString(), anyString(), anyString())).thenReturn(
                java.util.Optional.of("{\"action\":\"tool\",\"tool\":\"stock_count\",\"args\":{}}"));
        ToolLoopResult r = runner.run("LAB_A", "你是实验室助手。", "问题",
                List.of(new StockCountTool(datasourceService)), 1, 90);
        assertEquals(ToolLoopResult.Status.BUDGET_CUT, r.getStatus());
        assertNull(r.getAnswer());
        assertEquals(1, r.getLlmCalls());
        assertEquals(ToolStep.STEP_LIMIT, r.getSteps().get(r.getSteps().size() - 1).getKind());
    }

    @Test
    void llm_unavailable_honest_degradation() {
        when(llm.chat(anyString(), anyString(), anyString())).thenReturn(java.util.Optional.empty());
        ToolLoopResult r = runner.run("LAB_A", "你是实验室助手。", "问题",
                List.of(new StockCountTool(datasourceService)), 12, 90);
        assertEquals(ToolLoopResult.Status.LLM_UNAVAILABLE, r.getStatus());
        assertNull(r.getAnswer());
    }
}
