package com.bemodel.mcp;

import com.bemodel.llm.DeepSeekClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * MCP 端到端:真 Tomcat(random port)+真 Servlet 传输+真 JSON-RPC 序列;连本地 bemodel_platform+Flyway 种子。
 * DeepSeekClient 一律 mock 成 Optional.empty()(LLM 缺位=既有诚实降级):问数走能力菜单断言 isError:false。
 * spec §9 所述「喂查询计划 JSON 走真语义路径」不在此做——该 JSON 契约属 SemanticQaService 内部格式,
 * 硬造易碎;真问数带证据链由 Task 6 人工冒烟(带 DEEPSEEK_API_KEY)验收,renderAsk 证据小节由单测锁定。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpEndpointTest {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ObjectMapper objectMapper;
    @MockBean
    private DeepSeekClient deepSeekClient;

    @BeforeEach
    void noLlm() {
        when(deepSeekClient.chat(any(), any(), any())).thenReturn(Optional.empty());
    }

    private ResponseEntity<String> post(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // 规格要求 POST 的 Accept 同时含 json 与 event-stream,SDK 无状态传输按此校验
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM));
        return rest.postForEntity("/mcp", new HttpEntity<>(body, headers), String.class);
    }

    @Test
    void initializeHandshake() throws Exception {
        ResponseEntity<String> resp = post(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"it\",\"version\":\"0\"}}}");
        assertEquals(200, resp.getStatusCode().value());
        JsonNode result = objectMapper.readTree(resp.getBody()).path("result");
        assertEquals("2025-06-18", result.path("protocolVersion").asText(), "客户端请求的受支持版本必须原样回传");
        assertEquals("bemodel-mcp", result.path("serverInfo").path("name").asText());
        assertTrue(result.path("capabilities").path("tools").isObject(), "应声明 tools 能力");
    }

    @Test
    void initializedNotificationAccepted() {
        ResponseEntity<String> resp = post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertEquals(202, resp.getStatusCode().value(), "通知类消息=202 无 body(规格 MUST)");
    }

    @Test
    void toolsListExposesTwoReadOnlyTools() throws Exception {
        ResponseEntity<String> resp = post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        assertEquals(200, resp.getStatusCode().value());
        JsonNode tools = objectMapper.readTree(resp.getBody()).path("result").path("tools");
        assertEquals(2, tools.size());
        assertEquals("ask_data_question", tools.get(0).path("name").asText());
        assertEquals("get_metric_card", tools.get(1).path("name").asText());
        assertEquals("question", tools.get(0).path("inputSchema").path("required").get(0).asText());
    }

    @Test
    void askToolDegradesHonestlyWithoutLlm() throws Exception {
        ResponseEntity<String> resp = post(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"ask_data_question\",\"arguments\":{\"question\":\"内科有多少住院患者\"}}}");
        assertEquals(200, resp.getStatusCode().value());
        JsonNode result = objectMapper.readTree(resp.getBody()).path("result");
        assertFalse(result.path("isError").asBoolean(), "LLM 缺位=业务降级(能力菜单),不是工具故障(spec §5)");
        assertTrue(result.path("content").get(0).path("text").asText().length() > 10);
    }

    @Test
    void metricCardForSeedMetricShowsProbeReading() throws Exception {
        ResponseEntity<String> resp = post(
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"get_metric_card\",\"arguments\":{\"metric\":\"出院人数\"}}}");
        assertEquals(200, resp.getStatusCode().value());
        JsonNode result = objectMapper.readTree(resp.getBody()).path("result");
        assertFalse(result.path("isError").asBoolean());
        String text = result.path("content").get(0).path("text").asText();
        assertTrue(text.contains("出院人数"), "应按名称命中种子指标");
        assertTrue(text.contains("COUNT(DISTINCT 住院号)"), "应含公式");
        // V4__metric_monitor 已给全部种子指标绑探针(brief 写「无探针」与库不符):此处断言实测路径;
        // 「未绑定巡检探针」降级文案由 BemodelMcpToolsTest.renderMetricCardWithoutProbeStatesItHonestly 单测锁定
        assertTrue(text.contains("最近实测"), "种子指标已绑探针(V4),应如实带实测值");
    }

    @Test
    void unknownMetricIsToolExecutionError() throws Exception {
        ResponseEntity<String> resp = post(
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"get_metric_card\",\"arguments\":{\"metric\":\"根本不存在的指标XYZ\"}}}");
        JsonNode result = objectMapper.readTree(resp.getBody()).path("result");
        assertTrue(result.path("isError").asBoolean(), "查无此指标=执行错误(2025-11-25 口径,供模型自纠)");
        assertTrue(result.path("content").get(0).path("text").asText().contains("没有找到"));
    }

    @Test
    void unknownToolIsProtocolErrorNegative32602() throws Exception {
        ResponseEntity<String> resp = post(
                "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"nope\",\"arguments\":{}}}");
        assertEquals(200, resp.getStatusCode().value(), "JSON-RPC 协议错误走 200 响应体");
        assertEquals(-32602, objectMapper.readTree(resp.getBody()).path("error").path("code").asInt());
    }

    @Test
    void getMethodRejectedWith405() {
        ResponseEntity<String> resp = rest.exchange("/mcp", HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertEquals(405, resp.getStatusCode().value(), "无状态传输不开 SSE,GET 一律 405(规格合规)");
    }
}
