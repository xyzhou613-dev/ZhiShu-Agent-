package com.bemodel.compute;

import com.bemodel.common.BizException;
import com.bemodel.modeling.entity.Rule;
import com.bemodel.modeling.mapper.RuleMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ComputeRuleServiceTest {
    private final RuleMapper mapper = mock(RuleMapper.class);
    private final ComputeRuleService service = new ComputeRuleService(mapper, ComputeRuleEvaluatorTest.JSON);

    @Test void catalogCannotPretendMissingOrCustomizedDefinitionsAreExecutable() {
        when(mapper.selectList(any())).thenReturn(ComputeRuleEvaluator.CODES.stream().map(ComputeRuleEvaluatorTest::rule).toList());
        assertEquals(14,service.catalog().size());
        assertTrue(service.catalog().stream().allMatch(ComputeRuleService.Binding::checkAvailable));
        when(mapper.selectList(any())).thenReturn(java.util.List.of());
        assertTrue(service.catalog().stream().noneMatch(ComputeRuleService.Binding::checkAvailable));
    }

    @Test void previewRejectsVersionDriftAndUnregisteredCodeWithoutWriting() {
        Rule rule = ComputeRuleEvaluatorTest.rule("R-01");
        when(mapper.selectOne(any())).thenReturn(rule);
        var facts = ComputeRuleEvaluatorTest.FIXTURES.get("R-01").path("definition").path("sampleFacts");
        assertThrows(BizException.class,()->service.check("R-01",6,facts));
        assertThrows(BizException.class,()->service.check("R-09",7,facts));
        assertEquals(ComputeRuleEvaluator.Status.PASS,service.check("R-01",7,facts).result());
        verify(mapper,never()).insert(any(Rule.class));
        verify(mapper,never()).updateById(any(Rule.class));
    }

    @Test void expressionCannotSelectAnArbitraryHandlerOrImplementationVersion() throws Exception {
        Rule rule = ComputeRuleEvaluatorTest.rule("R-11");
        ObjectNode definition = (ObjectNode) ComputeRuleEvaluatorTest.JSON.readTree(rule.getExprJson());
        definition.put("handler","Runtime.exec");
        rule.setExprJson(definition.toString());
        assertThrows(BizException.class,()->service.validate(rule));
        definition.put("handler","freshness"); definition.put("evaluatorVersion","unknown"); rule.setExprJson(definition.toString());
        assertThrows(BizException.class,()->service.validate(rule));
        definition.put("evaluatorVersion",ComputeRuleEvaluator.VERSION); definition.putObject("parameters").put("maxAgeSeconds",0); rule.setExprJson(definition.toString());
        assertThrows(BizException.class,()->service.validate(rule));
    }
}
