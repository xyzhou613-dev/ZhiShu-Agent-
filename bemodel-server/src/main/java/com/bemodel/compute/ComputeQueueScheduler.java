package com.bemodel.compute;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @Profile("compute")
final class ComputeQueueScheduler {
    /** Publish only after the resource transaction has committed. */
    record ResourceChanged(String nodeKey) {}
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ComputeQueueScheduler.class);
    private final ComputeQueueService queue;private final boolean enabled;private final String node;
    ComputeQueueScheduler(ComputeQueueService queue,@Value("${compute.queue.recheck-enabled:false}")boolean enabled,@Value("${compute.rules.node-key:a800-demo}")String node){this.queue=queue;this.enabled=enabled;this.node=node;}
    @Scheduled(fixedDelayString="${compute.queue.recheck-delay-ms:30000}")void poll(){if(enabled)refresh(node);}
    @EventListener void changed(ResourceChanged event){if(enabled)refresh(event.nodeKey());}
    private void refresh(String key){try{queue.reevaluate(key);}catch(RuntimeException e){LOG.warn("Queue reevaluation failed for {}; next poll will retry: {}",key,e.getMessage());}}
}
