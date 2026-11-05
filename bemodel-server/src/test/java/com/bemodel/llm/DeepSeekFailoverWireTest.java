package com.bemodel.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * wire 级 failover golden（spec §6 三夹具）：JDK 内置 HttpServer 起本地假端点——
 * ①主路 500→备路 200 接管+审计两行；②主路挂起→备路在 T 内接管（预留式预算）；
 * ③连败两次熔断开路→第三次调用不再触主路。
 * 真实 HTTP 栈走 DeepSeekClient；LlmLogService 用 Mockito mock 捕获审计行。
 */
class DeepSeekFailoverWireTest {

    private HttpServer primaryServer;
    private HttpServer backupServer;
    private LlmLogService logService;
    private final AtomicInteger primaryHits = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        primaryServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backupServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        primaryServer.start();
        backupServer.start();
        primaryHits.set(0);
        logService = mock(LlmLogService.class);
    }

    @AfterEach
    void tearDown() {
        primaryServer.stop(0);
        backupServer.stop(0);
    }

    private void primaryAlways(int status, long sleepMs) {
        primaryServer.createContext("/chat/completions", (HttpExchange ex) -> {
            primaryHits.incrementAndGet();
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs); // HttpHandler 只声明 IOException，InterruptedException 须就地捕获
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            ex.sendResponseHeaders(status, -1);
            ex.close();
        });
    }

    private void backupAnswer(String content) {
        backupServer.createContext("/chat/completions", (HttpExchange ex) -> {
            byte[] body = ("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\""
                    + content + "\"}}]}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
    }

    private DeepSeekClient clientWithBackup(int timeoutSeconds) {
        DeepSeekProperties props = new DeepSeekProperties();
        props.setBaseUrl("http://127.0.0.1:" + primaryServer.getAddress().getPort());
        props.setApiKey("k-primary");
        props.setCallTimeouts(new HashMap<>(Map.of("CS_REPLY", timeoutSeconds)));
        props.getBackup().setBaseUrl("http://127.0.0.1:" + backupServer.getAddress().getPort());
        props.getBackup().setApiKey("k-backup");
        return new DeepSeekClient(props, new ObjectMapper(), logService);
    }

    @Test
    void 主路500_备路接管_审计两行() {
        primaryAlways(500, 0);
        backupAnswer("备路答案");
        DeepSeekClient client = clientWithBackup(30);

        Optional<String> out = client.chat("CS_REPLY", "sys", "user");

        assertEquals(Optional.of("备路答案"), out);
        assertEquals(1, primaryHits.get());
        verify(logService).log(eq("CS_REPLY"), eq("deepseek-v4-flash"), eq("primary"),
                anyString(), anyLong(), eq(false), contains("500"));
        verify(logService).log(eq("CS_REPLY"), eq("deepseek-v4-flash"), eq("backup"),
                anyString(), anyLong(), eq(true), isNull());
        verify(logService, times(2)).log(anyString(), anyString(), anyString(), anyString(),
                anyLong(), anyBoolean(), any());
    }

    @Test
    void 主路挂起_备路在T内接管() throws Exception {
        primaryAlways(200, 30_000); // 睡 30s 远超 T：模拟 2h15m 事故形态「连接活、响应体永不回」
        backupAnswer("挂起场景备路答案");
        // T=8s：首位候选让出 5s 预留 → 主路尝试预算≈3s（读超时 2~3s 触发），备路继承剩余
        DeepSeekClient client = clientWithBackup(8);

        long t0 = System.currentTimeMillis();
        Optional<String> out = client.chat("CS_REPLY", "sys", "user");
        long elapsed = System.currentTimeMillis() - t0;

        assertEquals(Optional.of("挂起场景备路答案"), out);
        assertTrue(elapsed < 8_000, "总耗时须受 T 约束（实际 " + elapsed + "ms），挂起型故障也在 T 内被接管");
        assertEquals(1, primaryHits.get());
    }

    @Test
    void 连败两次_第三次调用跳过主路_熔断生效() {
        primaryAlways(500, 0);
        backupAnswer("备路答案");
        DeepSeekClient client = clientWithBackup(30);

        client.chat("CS_REPLY", "sys", "user"); // 第1次：主路败（计数1）+备路成
        client.chat("CS_REPLY", "sys", "user"); // 第2次：主路再败（达阈2开路）+备路成
        Optional<String> third = client.chat("CS_REPLY", "sys", "user"); // 熔断开路：只触备路

        assertEquals(Optional.of("备路答案"), third);
        assertEquals(2, primaryHits.get(), "熔断开路后主路请求数不再增长");
        verify(logService, times(5)).log(anyString(), anyString(), anyString(), anyString(),
                anyLong(), anyBoolean(), any());
    }
}
