package com.bemodel.compute;

import com.bemodel.common.PageResult;
import com.bemodel.common.Result;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("compute")
@RequestMapping("/api/compute/target-selections")
final class ComputeTargetSelectionController {
    private final ComputeTargetSelectionService targets;
    ComputeTargetSelectionController(ComputeTargetSelectionService targets){this.targets=targets;}
    @PostMapping
    @ComputeAccess(ComputeOperation.TARGET_SELECT)
    Result<ComputeTargetSelectionService.Detail> select(@RequestBody JsonNode body){return Result.ok(targets.select(body));}
    @GetMapping
    @ComputeAccess(ComputeOperation.TARGET_QUERY)
    Result<PageResult<ComputeTargetSelectionService.Selection>> list(@RequestParam(required=false) Integer pageNum,
            @RequestParam(required=false) Integer pageSize,@RequestParam(required=false) Long requestId){
        return Result.ok(targets.list(PageResult.pageNum(pageNum),PageResult.pageSize(pageSize,20),requestId));
    }
    @GetMapping("/{id}")
    @ComputeAccess(ComputeOperation.TARGET_QUERY)
    Result<ComputeTargetSelectionService.Detail> detail(@PathVariable long id){return Result.ok(targets.detail(id));}
}
