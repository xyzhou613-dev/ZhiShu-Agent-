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
@RequestMapping("/api/compute/rule-evaluations")
final class ComputeRuleEvaluationController {
    private final ComputeRuleEvaluationService evaluations;

    ComputeRuleEvaluationController(ComputeRuleEvaluationService evaluations) {
        this.evaluations = evaluations;
    }

    @PostMapping
    Result<ComputeRuleEvaluationService.Detail> execute(@RequestBody JsonNode body) {
        return Result.ok(evaluations.execute(body));
    }

    @GetMapping
    Result<PageResult<ComputeRuleEvaluationService.Run>> list(@RequestParam(required = false) Integer pageNum,
                                                           @RequestParam(required = false) Integer pageSize) {
        return Result.ok(evaluations.list(PageResult.pageNum(pageNum), PageResult.pageSize(pageSize, 20)));
    }

    @GetMapping("/{id}")
    Result<ComputeRuleEvaluationService.Detail> detail(@PathVariable long id) {
        return Result.ok(evaluations.detail(id));
    }
}
