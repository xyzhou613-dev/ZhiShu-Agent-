package com.bemodel.compute;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * M-17 医疗接口隔离：算力模式下医疗专用控制器和 MCP 工具入口不对外提供。
 *
 * <p>返回 404 而不是 403，避免暴露“该医疗接口存在但被拒绝”；需要跨域联调时由
 * {@code compute.runtime.medical-apis-enabled=true} 显式放开。</p>
 */
@Component
@Profile("compute")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
final class MedicalApiIsolationFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(MedicalApiIsolationFilter.class);

    private final ComputeRuntimeSettings settings;
    private final ObjectMapper json;

    MedicalApiIsolationFilter(ComputeRuntimeSettings settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return settings.medicalApisEnabled()
                || ComputeRuntimeSettings.matchedIsolatedPath(request.getRequestURI()) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String matched = ComputeRuntimeSettings.matchedIsolatedPath(request.getRequestURI());
        LOG.debug("算力模式已隔离医疗入口 {}（请求 {}）", matched, request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(json.writeValueAsString(Map.of(
                "code", HttpServletResponse.SC_NOT_FOUND,
                "msg", "算力运行模式未启用该医疗专用接口")));
    }
}
