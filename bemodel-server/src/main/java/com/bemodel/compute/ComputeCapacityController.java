package com.bemodel.compute;
import com.bemodel.common.Result;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
@RestController @Profile("compute") @RequestMapping("/api/compute/capacity")
final class ComputeCapacityController {
    private final ComputeCapacityQuery query;
    ComputeCapacityController(ComputeCapacityQuery query){this.query=query;}
    @GetMapping
    @ComputeAccess(ComputeOperation.CAPACITY_PREVIEW)
    Result<?> preview(@RequestParam(defaultValue="a800-demo") String nodeKey,@RequestParam(required=false) Long jobId,@RequestParam(required=false) Long pausedDeploymentId,@RequestParam(required=false) Long restoreRevisionId){return Result.ok(query.preview(nodeKey,jobId,pausedDeploymentId,restoreRevisionId));}
}
