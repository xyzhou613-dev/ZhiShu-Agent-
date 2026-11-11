package com.bemodel.modeling.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bemodel.common.BizException;
import com.bemodel.common.StateMachine;
import com.bemodel.modeling.entity.Rule;
import com.bemodel.modeling.mapper.RuleMapper;
import com.bemodel.compute.ComputeRuleService;
import com.bemodel.auth.CurrentUser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RuleService extends ServiceImpl<RuleMapper, Rule> {
    private final ObjectProvider<ComputeRuleService> computeRules;

    public RuleService(ObjectProvider<ComputeRuleService> computeRules) { this.computeRules = computeRules; }

    private void validateCompute(Rule rule) {
        if (!"COMPUTE".equals(rule.getEngine())) return;
        ComputeRuleService checks = computeRules.getIfAvailable();
        if (checks == null) throw new BizException("算力规则只能在 compute 模式维护");
        checks.validate(rule);
    }

    public List<Rule> list(String conceptCode) {
        return lambdaQuery()
                .eq(conceptCode != null && !conceptCode.isBlank(), Rule::getConceptCode, conceptCode)
                .orderByAsc(Rule::getRuleCode)
                .list();
    }

    public Rule create(Rule rule) {
        if (lambdaQuery().eq(Rule::getRuleCode, rule.getRuleCode()).count() > 0) {
            throw new BizException("规则编码已存在: " + rule.getRuleCode());
        }
        rule.setStatus("DRAFT");
        rule.setVersion(1);
        validateCompute(rule);
        save(rule);
        return rule;
    }

    public Rule updateContent(Rule body) {
        Rule existing = body.getId() == null ? null : getById(body.getId());
        if (existing == null) throw new BizException("规则不存在");
        if ("COMPUTE".equals(existing.getEngine())) {
            if (!"COMPUTE".equals(body.getEngine()) || !existing.getRuleCode().equals(body.getRuleCode())
                    || !existing.getConceptCode().equals(body.getConceptCode())) throw new BizException("算力规则编码、引擎和关联概念不可改写");
            if (!existing.getVersion().equals(body.getVersion())) throw new BizException("规则已被其他人修改，请刷新后重试");
            validateCompute(body);
            boolean updated = lambdaUpdate().eq(Rule::getId, existing.getId()).eq(Rule::getVersion, existing.getVersion())
                    .eq(Rule::getEngine, "COMPUTE")
                    .set(Rule::getName, body.getName()).set(Rule::getExpression, body.getExpression())
                    .set(Rule::getExprJson, body.getExprJson()).set(Rule::getOwner, body.getOwner())
                    .set(Rule::getSeverity, body.getSeverity()).set(Rule::getRuleType, body.getRuleType())
                    .set(Rule::getStatus, "DRAFT").set(Rule::getVersion, existing.getVersion() + 1).update();
            if (!updated) throw new BizException("规则已被其他人修改，请刷新后重试");
        } else {
            validateCompute(body);
            updateById(body);
        }
        return getById(existing.getId());
    }

    public Rule transition(String ruleCode, String target) {
        Rule rule = lambdaQuery().eq(Rule::getRuleCode, ruleCode).one();
        if (rule == null) {
            throw new BizException("规则不存在: " + ruleCode);
        }
        StateMachine.check(rule.getStatus(), target);
        validateCompute(rule);
        if ("COMPUTE".equals(rule.getEngine())) {
            if (("PUBLISHED".equals(target) || "DEPRECATED".equals(target)) && !CurrentUser.hasAnyRole("REVIEWER","ADMIN"))
                throw new BizException("算力规则发布或废弃需要评审员或管理员");
            boolean updated = lambdaUpdate().eq(Rule::getId, rule.getId()).eq(Rule::getVersion, rule.getVersion())
                    .eq(Rule::getStatus, rule.getStatus()).eq(Rule::getEngine, "COMPUTE")
                    .set(Rule::getStatus, target)
                    .set(Rule::getVersion, "PUBLISHED".equals(target) ? Math.addExact(rule.getVersion(), 1) : rule.getVersion()).update();
            if (!updated) throw new BizException("规则已被其他人修改，请刷新后重试");
            return getById(rule.getId());
        }
        rule.setStatus(target);
        if ("PUBLISHED".equals(target)) {
            rule.setVersion(rule.getVersion() + 1);
        }
        updateById(rule);
        return rule;
    }
}
