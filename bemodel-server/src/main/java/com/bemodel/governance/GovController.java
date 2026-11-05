package com.bemodel.governance;

import com.bemodel.common.PageResult;
import com.bemodel.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/gov")
@RequiredArgsConstructor
public class GovController {

    private final GovService govService;

    /** 治理总览：物理表数/映射覆盖率/质量分/未处理问题 */
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        return Result.ok(govService.overview());
    }

    /** 每张物理表的治理画像 */
    @GetMapping("/tables")
    public Result<List<Map<String, Object>>> tables() {
        return Result.ok(govService.tables());
    }

    /** 最近一次扫描的问题清单（分页） */
    @GetMapping("/issues")
    public Result<PageResult<GovIssue>> issues(@RequestParam(required = false) Integer pageNum,
                                               @RequestParam(required = false) Integer pageSize) {
        return Result.ok(govService.issues(
                PageResult.pageNum(pageNum), PageResult.pageSize(pageSize, 20)));
    }

    /** 执行治理扫描（全部探针真实执行，实测耗时落库） */
    @PostMapping("/scan")
    public Result<Map<String, Object>> scan() {
        return Result.ok(govService.scan());
    }

    /** 问题标记已处理 */
    @PostMapping("/issues/{id}/resolve")
    public Result<Void> resolve(@PathVariable Long id) {
        govService.resolveIssue(id);
        return Result.ok(null);
    }

    /** 治理问题一键转 AI 客服工单（幂等，返回工单ID） */
    @PostMapping("/issues/{id}/ticket")
    public Result<Map<String, Object>> toTicket(@PathVariable Long id) {
        return Result.ok(govService.issueToTicket(id));
    }
}
