package com.bemodel.compute;

import com.bemodel.common.Result;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("compute")
@ConditionalOnProperty(name="compute.simulator.enabled",havingValue="true")
@RequestMapping("/api/compute/simulator")
final class ComputeSimulatorController {
    private final ComputeSimulatorService simulator;
    ComputeSimulatorController(ComputeSimulatorService simulator){this.simulator=simulator;}
    @GetMapping @ComputeAccess(ComputeOperation.SIMULATOR_QUERY)
    Result<?> info(){return Result.ok(simulator.info());}
    @PostMapping("/nodes/{nodeKey}/initialize") @ComputeAccess(ComputeOperation.SIMULATOR_CONTROL)
    Result<?> initialize(@PathVariable String nodeKey){return Result.ok(simulator.initialize(nodeKey));}
    @GetMapping("/nodes/{nodeKey}") @ComputeAccess(ComputeOperation.SIMULATOR_QUERY)
    Result<?> state(@PathVariable String nodeKey){return Result.ok(simulator.state(nodeKey));}
    @PutMapping("/nodes/{nodeKey}/state") @ComputeAccess(ComputeOperation.SIMULATOR_CONTROL)
    Result<?> configure(@PathVariable String nodeKey,@RequestBody JsonNode body){return Result.ok(simulator.configure(nodeKey,body));}
    @PostMapping("/nodes/{nodeKey}/observations") @ComputeAccess(ComputeOperation.SIMULATOR_CONTROL)
    Result<?> observe(@PathVariable String nodeKey,@RequestBody JsonNode body){return Result.ok(simulator.observe(nodeKey,body));}
    @PutMapping("/scenarios") @ComputeAccess(ComputeOperation.SIMULATOR_CONTROL)
    Result<?> scenario(@RequestBody JsonNode body){return Result.ok(simulator.scenario(body));}
    @GetMapping("/operations") @ComputeAccess(ComputeOperation.SIMULATOR_QUERY)
    Result<?> operation(@RequestParam String operationKey){return Result.ok(simulator.operation(operationKey));}
}
