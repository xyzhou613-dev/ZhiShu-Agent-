package com.bemodel.compute;
import com.bemodel.common.PageResult;
import com.bemodel.common.Result;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
@RestController @Profile("compute") @RequestMapping("/api/compute/queue")
final class ComputeQueueController {
    private final ComputeQueueService queue;
    ComputeQueueController(ComputeQueueService queue){this.queue=queue;}
    @PostMapping Result<?> enqueue(@RequestBody JsonNode body){return Result.ok(queue.enqueue(body));}
    @GetMapping Result<?> list(@RequestParam(defaultValue="a800-demo")String nodeKey,@RequestParam(defaultValue="WAITING")String status,@RequestParam(required=false)Integer pageNum,@RequestParam(required=false)Integer pageSize){return Result.ok(queue.list(nodeKey,status,PageResult.pageNum(pageNum),PageResult.pageSize(pageSize,20)));}
    @GetMapping("/{id}")Result<?> detail(@PathVariable long id){return Result.ok(queue.detail(id));}
    @PostMapping("/reevaluate")Result<?> reevaluate(@RequestBody JsonNode body){if(!body.path("nodeKey").isTextual()||body.path("nodeKey").asText().isBlank())throw new com.bemodel.common.BizException("重评需要 nodeKey");return Result.ok(queue.reevaluate(body.path("nodeKey").asText()));}
}
