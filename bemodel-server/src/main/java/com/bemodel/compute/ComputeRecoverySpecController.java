package com.bemodel.compute;
import com.bemodel.common.Result;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
@RestController @Profile("compute") @RequestMapping("/api/compute/deployments")
final class ComputeRecoverySpecController {
 private final ComputeRecoverySpecService specs;
 ComputeRecoverySpecController(ComputeRecoverySpecService specs){this.specs=specs;}
 @GetMapping @ComputeAccess(ComputeOperation.ACTION_RESULT_QUERY) Result<?> list(){return Result.ok(specs.list());}
 @PostMapping("/{id}/recovery-specs") @ComputeAccess(ComputeOperation.DATA_ASSET_WRITE) Result<?> register(@PathVariable long id,@RequestBody JsonNode body){return Result.ok(specs.register(id,body));}
}
