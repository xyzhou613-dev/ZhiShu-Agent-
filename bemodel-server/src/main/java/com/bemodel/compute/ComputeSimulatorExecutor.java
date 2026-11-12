package com.bemodel.compute;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** In-process persistent fake node/notification channel; no shell, container or network transport. */
@Component @Profile("compute")
@ConditionalOnProperty(name="compute.simulator.enabled",havingValue="true")
final class ComputeSimulatorExecutor implements ComputeActionExecutor {
    private final ComputeSimulatorService simulator;
    private final String key;
    ComputeSimulatorExecutor(ComputeSimulatorService simulator,ComputeRuntimeSettings runtime){
        if(!runtime.simulation())throw new IllegalStateException("模拟适配器不能用于真实模式");
        this.simulator=simulator;this.key="a800-sim-v1:"+UUID.nameUUIDFromBytes(runtime.nodeKey().getBytes(StandardCharsets.UTF_8));
    }
    public String key(){return key;}
    public boolean supports(String actionType){return ComputeSimulatorService.ACTIONS.contains(actionType);}
    public Reply execute(Command command,Duration timeout){return simulator.execute(command);}
    public Reply query(Command command,Duration timeout){return simulator.query(command);}
}
