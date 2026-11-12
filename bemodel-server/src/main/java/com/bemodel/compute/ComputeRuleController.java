package com.bemodel.compute;

import com.bemodel.common.Result;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Profile("compute")
@RequestMapping("/api/compute/rules")
public class ComputeRuleController {
    private final ComputeRuleService rules;
    public ComputeRuleController(ComputeRuleService rules) { this.rules = rules; }

    @GetMapping
    @ComputeAccess(ComputeOperation.RULE_CATALOG_QUERY)
    public Result<List<ComputeRuleService.Binding>> catalog() { return Result.ok(rules.catalog()); }

    public record PreviewRequest(Integer ruleVersion, JsonNode facts) {}

    /** Pure preview; supplied facts are NOT trusted scheduling evidence. */
    @PostMapping("/{code}/preview")
    @ComputeAccess(ComputeOperation.RULE_PREVIEW)
    public Result<ComputeRuleEvaluator.Result> preview(@PathVariable String code, @RequestBody PreviewRequest request) {
        return Result.ok(rules.check(code, request.ruleVersion(), request.facts()));
    }
}
