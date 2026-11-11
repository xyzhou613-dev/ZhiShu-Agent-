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
    public Result<List<ComputeRuleService.Binding>> catalog() { return Result.ok(rules.catalog()); }

    public record PreviewRequest(Integer ruleVersion, JsonNode facts) {}

    /** Editor/admin-only by existing SecurityConfig. Pure preview; supplied facts are NOT trusted scheduling evidence. */
    @PostMapping("/{code}/preview")
    public Result<ComputeRuleEvaluator.Result> preview(@PathVariable String code, @RequestBody PreviewRequest request) {
        return Result.ok(rules.check(code, request.ruleVersion(), request.facts()));
    }
}
