package com.bemodel.compute;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component @Profile("compute")
final class ComputeWorkflowScheduler {
    private final ComputeDatabase.Store store;private final ComputeWorkflowService workflows;private final boolean enabled;
    ComputeWorkflowScheduler(ComputeDatabase.Store store,ComputeWorkflowService workflows,@Value("${compute.workflow.enabled:false}")boolean enabled){this.store=store;this.workflows=workflows;this.enabled=enabled;}
    @Scheduled(fixedDelayString="${compute.workflow.poll-delay-ms:5000}") void poll(){if(!enabled)return;
        for(long id:store.jdbc().queryForList("SELECT id FROM workflow_run WHERE managed=TRUE AND status NOT IN ('COMPLETED','CANCELLED') AND (next_run_at IS NULL OR next_run_at<=?) ORDER BY id LIMIT 50",Long.class,java.time.LocalDateTime.now(java.time.Clock.systemUTC())))try{workflows.advance(id);}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Workflow {} will be checked again",id);}
    }
}
