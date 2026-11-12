package com.bemodel.compute;

import com.bemodel.common.BizException;
import com.bemodel.common.GlobalExceptionHandler;
import com.bemodel.common.PageResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ComputeActionControllerTest {
    final ComputeActionService actions=mock(ComputeActionService.class);
    MockMvc http;
    @BeforeEach void setup(){
        http=MockMvcBuilders.standaloneSetup(new ComputeActionController(actions))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new ComputeAccessInterceptor(mock(JdbcTemplate.class),new ObjectMapper())).build();
    }
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();}
    void role(String role){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("tester",null,List.of(new SimpleGrantedAuthority("ROLE_"+role))));}

    @Test void listsWithTheExistingPaginationEnvelopeAndNormalizedParameters()throws Exception {
        role("EDITOR");when(actions.list(1,200,7L,"fixed", "UNKNOWN")).thenReturn(PageResult.of(List.of(),0,1,200));
        http.perform(get("/api/compute/execution-actions?workflowId=7&operationKey=fixed&status=UNKNOWN&pageNum=0&pageSize=999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.list").isArray())
                .andExpect(jsonPath("$.data.pageSize").value(200));
        verify(actions).list(1,200,7L,"fixed","UNKNOWN");
    }
    @Test void permitsEditorReconciliationButReservesManualRetryForAdmin()throws Exception {
        role("EDITOR");http.perform(post("/api/compute/execution-actions/7/reconcile")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        http.perform(post("/api/compute/execution-actions/7/retry")).andExpect(status().isForbidden());
        verify(actions).reconcile(7);verify(actions,never()).retry(anyLong());
        role("ADMIN");when(actions.retry(7)).thenThrow(new BizException("未知结果请先核对"));
        http.perform(post("/api/compute/execution-actions/7/retry")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(500)).andExpect(jsonPath("$.msg").value("未知结果请先核对"));
    }
    @Test void deniesViewerAndReviewerAccessToRawActionInputsAndReceipts()throws Exception {
        for(String role:List.of("VIEWER","REVIEWER")){
            role(role);
            http.perform(get("/api/compute/execution-actions/7")).andExpect(status().isForbidden());
            http.perform(get("/api/compute/execution-actions")).andExpect(status().isForbidden());
            http.perform(post("/api/compute/execution-actions/7/reconcile")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(actions);
    }
    @Test void detailAndBusinessErrorsUseTheExistingResultEnvelope()throws Exception {
        role("ADMIN");when(actions.detail(7)).thenReturn(new ComputeActionService.Detail(null,List.of()));
        when(actions.detail(8)).thenThrow(new BizException("执行动作不存在"));
        http.perform(get("/api/compute/execution-actions/7")).andExpect(status().isOk()).andExpect(jsonPath("$.data.events").isArray());
        http.perform(get("/api/compute/execution-actions/8")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(500));
    }
}
