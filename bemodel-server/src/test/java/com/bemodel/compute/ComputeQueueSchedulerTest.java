package com.bemodel.compute;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ComputeQueueSchedulerTest {
    @Test void disabledPollingDoesNotWriteAndEnabledEventsAndPollingRetryFailures(){
        var queue=mock(ComputeQueueService.class);
        var disabled=new ComputeQueueScheduler(queue,false,"a800-demo");disabled.poll();disabled.changed(new ComputeQueueScheduler.ResourceChanged("a800-demo"));verifyNoInteractions(queue);
        var enabled=new ComputeQueueScheduler(queue,true,"a800-demo");enabled.poll();enabled.changed(new ComputeQueueScheduler.ResourceChanged("other"));
        verify(queue).reevaluate("a800-demo");verify(queue).reevaluate("other");
        when(queue.reevaluate("a800-demo")).thenThrow(new IllegalStateException("offline"));assertDoesNotThrow(enabled::poll);
        verify(queue,times(2)).reevaluate("a800-demo");
    }
}
