package com.bemodel.llm;

import com.bemodel.common.PageResult;
import com.bemodel.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/llm")
@RequiredArgsConstructor
public class LlmLogController {

    private final LlmLogService llmLogService;
    private final DeepSeekClient deepSeekClient;

    @GetMapping("/log/list")
    public Result<PageResult<LlmLog>> recent(@RequestParam(required = false) Integer pageNum,
                                             @RequestParam(required = false) Integer pageSize) {
        return Result.ok(llmLogService.page(
                PageResult.pageNum(pageNum), PageResult.pageSize(pageSize, 20)));
    }

    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        return Result.ok(llmLogService.stats());
    }

    /** 主备端点健康视图（借鉴 4）：顶栏消费；host 已脱敏，不回 Key 与完整 URL */
    @GetMapping("/routes")
    public Result<List<Map<String, Object>>> routes() {
        return Result.ok(deepSeekClient.routes());
    }
}
