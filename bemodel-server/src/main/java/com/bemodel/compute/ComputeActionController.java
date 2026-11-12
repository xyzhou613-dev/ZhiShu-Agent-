package com.bemodel.compute;

import com.bemodel.common.PageResult;
import com.bemodel.common.Result;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("compute")
@RequestMapping("/api/compute/execution-actions")
final class ComputeActionController {
    private final ComputeActionService actions;
    ComputeActionController(ComputeActionService actions) { this.actions=actions; }

    @GetMapping
    @ComputeAccess(ComputeOperation.ACTION_RESULT_QUERY)
    Result<?> list(@RequestParam(required=false) Integer pageNum, @RequestParam(required=false) Integer pageSize,
                   @RequestParam(required=false) Long workflowId, @RequestParam(required=false) String operationKey,
                   @RequestParam(required=false) String status) {
        return Result.ok(actions.list(PageResult.pageNum(pageNum),PageResult.pageSize(pageSize,20),workflowId,operationKey,status));
    }
    @GetMapping("/{id}")
    @ComputeAccess(ComputeOperation.ACTION_RESULT_QUERY)
    Result<?> detail(@PathVariable long id) { return Result.ok(actions.detail(id)); }

    @PostMapping("/{id}/reconcile")
    @ComputeAccess(ComputeOperation.ACTION_RECONCILE)
    Result<?> reconcile(@PathVariable long id) { return Result.ok(actions.reconcile(id)); }

    @PostMapping("/{id}/retry")
    @ComputeAccess(ComputeOperation.ACTION_RETRY)
    Result<?> retry(@PathVariable long id) { return Result.ok(actions.retry(id)); }
}
