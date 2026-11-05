package com.bemodel.trace;

import com.bemodel.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 证据链 trace 页（D3）：GET /api/trace?type=QA|CONCEPT|MAPPING&key=…，五段式证据一次取齐 */
@RestController
@RequestMapping("/api/trace")
@RequiredArgsConstructor
public class TraceController {

    private final TraceService traceService;

    @GetMapping
    public Result<Map<String, Object>> trace(@RequestParam String type, @RequestParam String key) {
        return Result.ok(traceService.assemble(type, key));
    }
}
