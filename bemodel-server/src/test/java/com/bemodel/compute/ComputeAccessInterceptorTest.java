package com.bemodel.compute;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ComputeAccessInterceptorTest {
    private final RecordingJdbc jdbc = new RecordingJdbc();
    private final ComputeAccessInterceptor interceptor =
            new ComputeAccessInterceptor(jdbc, new ObjectMapper().findAndRegisterModules());

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void undeclaredComputeEndpointIsDeniedAndAudited() throws Exception {
        MockHttpServletResponse response = handle("viewer", TestController.class.getMethod("undeclaredMethod"));
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("尚未声明访问权限"));
        assertEquals("UNDECLARED", jdbc.operation);
        assertEquals(1, jdbc.updates);
    }

    @Test
    void viewerCannotSubmitButEditorCan() throws Exception {
        MockHttpServletResponse denied = handle("viewer", TestController.class.getMethod("submit"));
        assertEquals(403, denied.getStatus());
        assertTrue(denied.getContentAsString().contains("无权执行"));
        assertEquals("REQUEST_SUBMIT", jdbc.operation);
        assertEquals("test-viewer", jdbc.actor);

        jdbc.reset();
        MockHttpServletResponse allowed = handle("editor", TestController.class.getMethod("submit"));
        assertEquals(200, allowed.getStatus());
        assertEquals(0, jdbc.updates);
    }

    @Test
    void viewerCanReadGroupsButNotRequestDetails() throws Exception {
        assertEquals(200, handle("viewer", TestController.class.getMethod("read")).getStatus());
        assertEquals(403, handle("viewer", TestController.class.getMethod("requestDetail")).getStatus());
    }

    @Test
    void conflictResolutionIsReviewerOrAdminOnly() throws Exception {
        assertEquals(200, handle("reviewer", TestController.class.getMethod("resolve")).getStatus());
        assertEquals(200, handle("admin", TestController.class.getMethod("resolve")).getStatus());
        assertEquals(403, handle("editor", TestController.class.getMethod("resolve")).getStatus());
    }

    @Test
    void classLevelAnnotationAppliesToItsMethods() throws Exception {
        login("viewer");
        var request = new MockHttpServletRequest("GET", "/api/compute/field-policies");
        var response = new MockHttpServletResponse();
        HandlerMethod handler = new HandlerMethod(new ClassLevelController(),
                ClassLevelController.class.getMethod("read"));
        try {
            interceptor.preHandle(request, response, handler);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertEquals(200, response.getStatus());
    }

    private MockHttpServletResponse handle(String role, java.lang.reflect.Method method) throws Exception {
        login(role);
        var request = new MockHttpServletRequest("POST", "/api/compute/test");
        var response = new MockHttpServletResponse();
        HandlerMethod handler = new HandlerMethod(new TestController(), method);
        try {
            interceptor.preHandle(request, response, handler);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return response;
    }

    private static void login(String role) {
        var authentication = new UsernamePasswordAuthenticationToken("test-" + role, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase())));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @SuppressWarnings("unused")
    static class TestController {
        @ComputeAccess(ComputeOperation.REQUEST_SUBMIT)
        public void submit() {
        }

        public void undeclaredMethod() {
        }

        @ComputeAccess(ComputeOperation.RESOURCE_QUERY)
        public void read() {
        }

        @ComputeAccess(ComputeOperation.REQUEST_QUERY)
        public void requestDetail() {
        }

        @ComputeAccess(ComputeOperation.RULE_CONFLICT_RESOLVE)
        public void resolve() {
        }
    }

    @ComputeAccess(ComputeOperation.FIELD_POLICY_QUERY)
    static class ClassLevelController {
        public void read() {
        }
    }

    static class RecordingJdbc extends JdbcTemplate {
        int updates;
        String actor;
        String operation;

        @Override
        public int update(String sql, Object... args) {
            updates++;
            actor = String.valueOf(args[0]);
            operation = String.valueOf(args[1]);
            return 1;
        }

        void reset() {
            updates = 0;
            actor = null;
            operation = null;
        }
    }
}
