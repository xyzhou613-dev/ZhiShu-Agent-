package com.bemodel.compute;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ComputeWorkflowSchedulerTest {
    @Test void disabledPollDoesNotAccessDatabase(){var store=mock(ComputeDatabase.Store.class);var service=mock(ComputeWorkflowService.class);new ComputeWorkflowScheduler(store,service,false).poll();verifyNoInteractions(store,service);}
    @Test void oneFailureDoesNotPreventOtherDueWorkflows(){var store=mock(ComputeDatabase.Store.class);var jdbc=mock(JdbcTemplate.class);var service=mock(ComputeWorkflowService.class);when(store.jdbc()).thenReturn(jdbc);when(jdbc.queryForList(anyString(),eq(Long.class),any(Object.class))).thenReturn(List.of(7L,8L));when(service.advance(7)).thenThrow(new IllegalStateException());new ComputeWorkflowScheduler(store,service,true).poll();verify(service).advance(7);verify(service).advance(8);}
}
