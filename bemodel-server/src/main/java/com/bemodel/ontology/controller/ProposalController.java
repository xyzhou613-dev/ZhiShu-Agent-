package com.bemodel.ontology.controller;

import com.bemodel.common.PageResult;
import com.bemodel.common.Result;
import com.bemodel.ontology.entity.OntologyProposal;
import com.bemodel.ontology.service.OntologyProposalService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * AI 提案队列（P1a）。权限零特判：POST /api/** 全局已限 ADMIN/EDITOR；
 * list 与 miss 看板同口径对全部登录角色可见（提案里的说法即 miss 里的说法，不新增暴露面）。
 */
@RestController
@RequestMapping("/api/proposal")
@RequiredArgsConstructor
public class ProposalController {

    private final OntologyProposalService proposalService;

    @GetMapping("/list")
    public Result<PageResult<OntologyProposal>> list(@RequestParam(required = false) String status,
                                                     @RequestParam(required = false) Integer pageNum,
                                                     @RequestParam(required = false) Integer pageSize) {
        return Result.ok(proposalService.page(status,
                PageResult.pageNum(pageNum), PageResult.pageSize(pageSize, 20)));
    }

    /** 手动触发一次提案生成（与定时器同一逻辑；产生 ≤ Top-N 次 LLM 调用） */
    @PostMapping("/run")
    public Result<Map<String, Object>> run() {
        return Result.ok(proposalService.run());
    }

    /** 采纳：NEW_CONCEPT 建 DRAFT 概念（自动不发布），ATTACH_TERM 挂方言术语 */
    @PostMapping("/{id}/adopt")
    public Result<OntologyProposal> adopt(@PathVariable Long id) {
        return Result.ok(proposalService.adopt(id));
    }

    /** 驳回：释放信号回 miss 池（下次 run 可再入队） */
    @PostMapping("/{id}/reject")
    public Result<OntologyProposal> reject(@PathVariable Long id,
                                           @RequestBody(required = false) Map<String, String> body) {
        return Result.ok(proposalService.reject(id, body == null ? null : body.get("reason")));
    }
}
