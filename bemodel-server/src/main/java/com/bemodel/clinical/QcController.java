package com.bemodel.clinical;

import com.bemodel.common.PageResult;
import com.bemodel.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/qc")
@RequiredArgsConstructor
public class QcController {

    private final QcService qcService;

    @GetMapping("/records")
    public Result<PageResult<Map<String, Object>>> records(@RequestParam(required = false) String keyword,
                                                           @RequestParam(required = false) Integer pageNum,
                                                           @RequestParam(required = false) Integer pageSize) {
        return Result.ok(qcService.recordsPage(keyword,
                PageResult.pageNum(pageNum), PageResult.pageSize(pageSize, 20)));
    }

    @PostMapping("/check/{recordId}")
    public Result<Map<String, Object>> check(@PathVariable String recordId) {
        return Result.ok(qcService.check(recordId));
    }

    @PostMapping("/check-all")
    public Result<Map<String, Object>> checkAll() {
        return Result.ok(qcService.checkAll());
    }

    @GetMapping("/result/{recordId}")
    public Result<QcResult> latestResult(@PathVariable String recordId) {
        return Result.ok(qcService.latestResult(recordId));
    }
}
