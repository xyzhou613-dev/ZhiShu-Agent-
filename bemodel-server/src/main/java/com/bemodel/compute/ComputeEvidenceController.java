package com.bemodel.compute;

import com.bemodel.common.PageResult;
import com.bemodel.common.Result;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("compute")
@RequestMapping("/api/compute/evidence-snapshots")
final class ComputeEvidenceController {
    private final ComputeEvidenceService evidence;
    ComputeEvidenceController(ComputeEvidenceService evidence) { this.evidence = evidence; }

    @PostMapping
    Result<ComputeEvidenceService.Detail> capture(@RequestBody JsonNode body) { return Result.ok(evidence.capture(body)); }

    @GetMapping
    Result<PageResult<ComputeEvidenceService.Snapshot>> list(@RequestParam(required=false) Integer pageNum,
                                                            @RequestParam(required=false) Integer pageSize) {
        return Result.ok(evidence.list(PageResult.pageNum(pageNum), PageResult.pageSize(pageSize, 20)));
    }

    @GetMapping("/{id}")
    Result<ComputeEvidenceService.Detail> detail(@PathVariable long id) { return Result.ok(evidence.detail(id)); }
}
