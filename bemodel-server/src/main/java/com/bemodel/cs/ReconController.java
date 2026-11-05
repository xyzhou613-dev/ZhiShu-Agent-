package com.bemodel.cs;

import com.bemodel.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 跨库核对差异登记查询（V31）：GET 全角色可读。 */
@RestController
@RequestMapping("/api/recon")
@RequiredArgsConstructor
public class ReconController {

    private final ReconciliationService reconciliationService;

    @GetMapping("/diffs")
    public Result<List<ReconDiff>> diffs(@RequestParam String runId) {
        return Result.ok(reconciliationService.diffsOf(runId));
    }
}
