package com.bemodel.compute;
import com.bemodel.common.*;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
@RestController @Profile("compute") @RequestMapping("/api/compute/workflows")
final class ComputeWorkflowController {
    private final ComputeWorkflowService workflows;
    ComputeWorkflowController(ComputeWorkflowService workflows){this.workflows=workflows;}
    record Create(long reservationReceiptId){}
    @PostMapping @ComputeAccess(ComputeOperation.WORKFLOW_ADVANCE) Result<?> create(@RequestBody Create body){return Result.ok(workflows.create(body.reservationReceiptId()));}
    @GetMapping @ComputeAccess(ComputeOperation.WORKFLOW_QUERY) Result<?> list(@RequestParam(required=false)Integer pageNum,@RequestParam(required=false)Integer pageSize){return Result.ok(workflows.list(PageResult.pageNum(pageNum),PageResult.pageSize(pageSize,20)));}
    @GetMapping("/{id}") @ComputeAccess(ComputeOperation.WORKFLOW_QUERY) Result<?> detail(@PathVariable long id){return Result.ok(workflows.detail(id));}
    @PostMapping("/{id}/advance") @ComputeAccess(ComputeOperation.WORKFLOW_ADVANCE) Result<?> advance(@PathVariable long id){return Result.ok(workflows.advance(id));}
    @PostMapping("/{id}/stop") @ComputeAccess(ComputeOperation.JOB_STOP) Result<?> stop(@PathVariable long id){return Result.ok(workflows.stop(id,false));}
    @PostMapping("/{id}/cancel") @ComputeAccess(ComputeOperation.REQUEST_CANCEL) Result<?> cancel(@PathVariable long id){return Result.ok(workflows.stop(id,true));}
}
