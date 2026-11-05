package com.bemodel.mcp;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 只读开放端点(借鉴 1「查询即应用」):
 * - 无状态 Servlet 传输:POST /mcp 单端点,应答恒 application/json;GET/DELETE 一律 405;
 *   不下发会话头(规格 MAY 项,省略合规,演示期最省心)。
 * - capabilities 只声明 tools;resources/prompts/sampling 不声明不实现。
 * - 挂载用 ServletRegistrationBean 显式映射 /mcp 与 /mcp/*;asyncSupported 必须保持 true(传输内部用 startAsync)。
 * - 鉴权=无(演示期拍板,spec §7):落 SecurityConfig anyRequest().permitAll() 兜底;
 *   升级缝=后续加一个 servlet filter,协议与工具层零改动。
 */
@Configuration
public class McpConfig {

    @Bean
    public HttpServletStatelessServerTransport mcpTransport() {
        return HttpServletStatelessServerTransport.builder()
                .messageEndpoint("/mcp")
                .build();
    }

    @Bean
    public McpStatelessSyncServer mcpServer(HttpServletStatelessServerTransport mcpTransport, BemodelMcpTools tools) {
        return McpServer.sync(mcpTransport)
                .serverInfo("bemodel-mcp", "1.0.0")
                .capabilities(ServerCapabilities.builder().tools(true).build())
                .tools(tools.askDataQuestion(), tools.getMetricCard())
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServletStatelessServerTransport> mcpServlet(
            HttpServletStatelessServerTransport mcpTransport) {
        ServletRegistrationBean<HttpServletStatelessServerTransport> reg =
                new ServletRegistrationBean<>(mcpTransport, "/mcp", "/mcp/*");
        reg.setName("mcpServlet");
        reg.setAsyncSupported(true);
        return reg;
    }
}
