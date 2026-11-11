package com.bemodel.compute;

import com.bemodel.auth.CurrentUser;
import com.bemodel.common.BizException;
import com.bemodel.common.Result;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@Profile("compute")
@RequestMapping("/api/compute")
public class ComputeReleaseController {
    private final ComputeReleaseBridge bridge;
    private final ComputeRuleService rules;
    private final JdbcTemplate platform;
    private final ObjectMapper json;
    ComputeReleaseController(ComputeReleaseBridge bridge,ComputeRuleService rules,JdbcTemplate platform,ObjectMapper json) {
        this.bridge=bridge; this.rules=rules; this.platform=platform; this.json=json;
    }
    @GetMapping("/releases") Result<?> history() { return Result.ok(Map.of("active",bridge.active(),"history",bridge.history())); }
    @GetMapping("/releases/sets/{id}") Result<?> snapshot(@PathVariable long id) { return Result.ok(bridge.snapshot(id)); }
    @GetMapping("/releases/preflight") Result<?> preflight() {
        var errors=new ArrayList<String>();
        for(var binding:rules.catalog()) {
            if(!binding.checkAvailable()) errors.add(binding.ruleCode()+": "+binding.message());
            else if(!"PUBLISHED".equals(binding.rule().getStatus())) errors.add(binding.ruleCode()+": 尚未发布");
        }
        try { bridge.prepare(rules.catalog().stream().map(ComputeRuleService.Binding::rule).filter(Objects::nonNull).toList()); }
        catch(BizException e) { if(errors.isEmpty()) errors.add(e.getMessage()); }
        return Result.ok(Map.of("ready",errors.isEmpty(),"errors",errors,"formulaVersion",ComputeVersions.FORMULAS,"evaluatorVersion",ComputeRuleEvaluator.VERSION));
    }
    @PostMapping("/releases/{id}/sync") Result<?> retry(@PathVariable long id) {
        reviewer(); return Result.ok(bridge.sync(id));
    }
    @GetMapping("/rule-conflicts") Result<?> conflicts() { return Result.ok(platform.queryForList("SELECT * FROM bm_compute_rule_conflict ORDER BY id DESC LIMIT 100")); }
    @PostMapping("/rule-conflicts") @Transactional Result<?> conflict(@RequestBody JsonNode body) {
        JsonNode codes=body.path("ruleCodes");
        if(!codes.isArray() || codes.size()<2 || codes.size()>14) throw new BizException("冲突需列出 2 至 14 个规则编号");
        var seen=new HashSet<String>();
        for(var c:codes) if(!c.isTextual() || !ComputeRuleEvaluator.CODES.contains(c.asText()) || !seen.add(c.asText())) throw new BizException("规则编号非法或重复");
        String description=text(body,"description",512),priority=text(body,"priority",64);
        long id=ComputeReleaseBridge.insert(platform,"INSERT INTO bm_compute_rule_conflict(rule_codes,description,priority,created_by) VALUES(?,?,?,?)",codes.toString(),description,priority,CurrentUser.username());
        bridge.audit("RULE_CONFLICT_OPENED",Long.toString(id),Map.of("ruleCodes",seen));
        return Result.ok(Map.of("id",id));
    }
    @PostMapping("/rule-conflicts/{id}/resolve") @Transactional Result<?> resolve(@PathVariable long id,@RequestBody JsonNode body) {
        reviewer(); String conclusion=text(body,"resolution",512);
        if(platform.update("UPDATE bm_compute_rule_conflict SET status='RESOLVED',resolution=?,resolved_by=?,resolved_at=UTC_TIMESTAMP(6) WHERE id=? AND status='OPEN'",conclusion,CurrentUser.username(),id)!=1)
            throw new BizException("冲突不存在或已处理，请刷新");
        bridge.audit("RULE_CONFLICT_RESOLVED",Long.toString(id),Map.of("resolution",conclusion));
        return Result.ok();
    }
    private void reviewer() { if(!CurrentUser.hasAnyRole("ADMIN","REVIEWER")) throw new BizException("需要评审员或管理员"); }
    private String text(JsonNode body,String name,int max) { JsonNode v=body.path(name); if(!v.isTextual() || v.asText().isBlank() || v.asText().length()>max) throw new BizException(name+" 必须是非空文本且不超过 "+max+" 字符"); return v.asText().trim(); }
}
