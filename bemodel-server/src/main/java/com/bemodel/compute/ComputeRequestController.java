package com.bemodel.compute;

import com.bemodel.common.PageResult;
import com.bemodel.common.Result;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("compute")
@RequestMapping("/api/compute/requests")
final class ComputeRequestController {
    private final ComputeRequestService requests;

    ComputeRequestController(ComputeRequestService requests) {
        this.requests = requests;
    }

    @PostMapping
    Result<ComputeRequestService.Request> submit(@RequestBody JsonNode body) {
        return Result.ok(requests.submit(body));
    }

    @GetMapping
    Result<PageResult<ComputeRequestService.Request>> list(@RequestParam(required = false) Integer pageNum,
                                                         @RequestParam(required = false) Integer pageSize) {
        return Result.ok(requests.list(PageResult.pageNum(pageNum), PageResult.pageSize(pageSize, 20)));
    }

    @GetMapping("/{id}")
    Result<ComputeRequestService.Detail> detail(@PathVariable long id) {
        return Result.ok(requests.detail(id));
    }

    @PostMapping("/{id}/cancel")
    Result<ComputeRequestService.Request> cancel(@PathVariable long id) {
        return Result.ok(requests.cancel(id));
    }

    @PostMapping("/{id}/jobs/{jobId}/retry")
    Result<ComputeRequestService.Job> retry(@PathVariable long id, @PathVariable long jobId) {
        return Result.ok(requests.retry(id, jobId));
    }
}
