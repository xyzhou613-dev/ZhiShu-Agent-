package com.bemodel.compute;

import com.bemodel.auth.CurrentUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * M-16 统一权限拦截器：所有 {@code /api/compute/**} 请求在进入控制器前按 {@link ComputeOperation} 校验角色。
 *
 * <p>没有声明 {@link ComputeAccess} 的算力接口直接拒绝（fail-closed）；被拒绝的操作写入
 * {@code bm_compute_operation_audit}，便于回答“谁在什么时候尝试了什么被拒绝”。审计失败不影响 403 返回。</p>
 */
@Component
@Profile("compute")
final class ComputeAccessInterceptor implements HandlerInterceptor {
    private static final Logger LOG = LoggerFactory.getLogger(ComputeAccessInterceptor.class);

    private final JdbcTemplate platform;
    private final ObjectMapper json;

    ComputeAccessInterceptor(JdbcTemplate platform, ObjectMapper json) {
        this.platform = platform;
        this.json = json;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        ComputeAccess access = method.getMethodAnnotation(ComputeAccess.class);
        if (access == null) {
            access = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), ComputeAccess.class);
        }
        if (access == null) {
            return deny(request, response, null, "该算力接口尚未声明访问权限，已拒绝");
        }
        ComputeOperation operation = access.value();
        if (CurrentUser.hasAnyRole(operation.roles().toArray(String[]::new))) {
            return true;
        }
        return deny(request, response, operation, "当前角色无权执行算力操作：" + operation.description());
    }

    private boolean deny(HttpServletRequest request, HttpServletResponse response, ComputeOperation operation,
                         String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(json.writeValueAsString(Map.of("code", 403, "msg", message)));
        audit(request, operation, message);
        return false;
    }

    private void audit(HttpServletRequest request, ComputeOperation operation, String reason) {
        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("decision", "DENIED");
            detail.put("reason", reason);
            detail.put("method", request.getMethod());
            detail.put("path", request.getRequestURI());
            detail.put("requiredRoles", operation == null ? List.of() : operation.roles());
            platform.update("""
                            INSERT INTO bm_compute_operation_audit(actor,operation,subject_ref,detail)
                            VALUES(?,?,?,?)
                            """,
                    truncate(Objects.requireNonNullElse(CurrentUser.username(), "anonymous"), 64),
                    operation == null ? "UNDECLARED" : operation.name(),
                    truncate(request.getMethod() + " " + request.getRequestURI(), 128),
                    json.writeValueAsString(detail));
        } catch (RuntimeException | JsonProcessingException e) {
            LOG.warn("记录算力权限拒绝审计失败: {}", e.getMessage());
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
