package com.bemodel.cs;

import com.bemodel.common.PageResult;
import com.bemodel.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/cs")
@RequiredArgsConstructor
public class CsController {

    private final CsService csService;
    private final ClarifyService clarifyService;

    /** 工单智能诊断（打开即自动完成，返回排查路径/结论/证据/建议/客户话术） */
    @GetMapping("/ticket/{id}/diagnosis")
    public Result<Map<String, Object>> diagnosis(@PathVariable Long id) {
        return Result.ok(csService.diagnosis(id));
    }

    /** 一键处置：生成退费申请 + 处置单 + 工单办结 */
    @PostMapping("/ticket/{id}/refund")
    public Result<Map<String, Object>> refund(@PathVariable Long id,
                                              @RequestParam(defaultValue = "客服 小周") String operator) {
        return Result.ok(csService.refundAction(id, operator));
    }

    /** 问一问：自然语言提问（scene=CS 客服 / ANALYTICS 智能问数，缺省 CS），路由到平台真实能力作答 */
    @PostMapping("/ask")
    public Result<Map<String, Object>> ask(@RequestBody Map<String, String> body) {
        return Result.ok(csService.ask(body.get("question"), body.get("scene")));
    }

    /** 澄清续跑（D2b）：body {supplement}；q'=原问题+补充重入路由，返回真实答案/二轮澄清卡/兜底菜单 */
    @PostMapping("/clarify/{id}/answer")
    public Result<Map<String, Object>> clarifyAnswer(@PathVariable Long id,
                                                     @RequestBody Map<String, String> body) {
        return Result.ok(csService.clarifyAnswer(id, body.get("supplement")));
    }

    /** 路由反馈（viewer 也可提交；错例回流进路由提示词） */
    @PostMapping("/feedback")
    public Result<CsFeedback> feedback(@RequestBody Map<String, Object> body) {
        Object correct = body.get("correct");
        Integer c = correct == null ? 0
                : (Boolean.parseBoolean(String.valueOf(correct)) || "1".equals(String.valueOf(correct)) ? 1 : 0);
        return Result.ok(csService.saveFeedback(
                body.get("question") == null ? null : String.valueOf(body.get("question")),
                body.get("intent") == null ? null : String.valueOf(body.get("intent")),
                body.get("router") == null ? null : String.valueOf(body.get("router")),
                c,
                body.get("comment") == null ? null : String.valueOf(body.get("comment"))));
    }

    /** 反馈列表（分页，管理查看） */
    @GetMapping("/feedback/list")
    public Result<PageResult<CsFeedback>> feedbackList(@RequestParam(required = false) Integer pageNum,
                                                       @RequestParam(required = false) Integer pageSize) {
        return Result.ok(csService.feedbackPage(
                PageResult.pageNum(pageNum), PageResult.pageSize(pageSize, 20)));
    }

    /** 澄清任务列表（维护者可见性）：PENDING/GAVE_UP/RESOLVED，证据在任务行（原问题+LLM 判断+用户补充） */
    @GetMapping("/clarify/list")
    public Result<PageResult<ClarifyTask>> clarifyList(@RequestParam(required = false) String status,
                                                       @RequestParam(required = false) Integer pageNum,
                                                       @RequestParam(required = false) Integer pageSize) {
        return Result.ok(clarifyService.page(status,
                PageResult.pageNum(pageNum), PageResult.pageSize(pageSize, 20)));
    }
}
