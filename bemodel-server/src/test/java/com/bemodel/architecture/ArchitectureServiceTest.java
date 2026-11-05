package com.bemodel.architecture;

import com.bemodel.datasource.service.DatasourceService;
import com.bemodel.datasource.service.MappingService;
import com.bemodel.datasource.service.SchemaScanService;
import com.bemodel.datasource.entity.PhysicalTable;
import com.bemodel.modeling.service.RuleService;
import com.bemodel.ontology.entity.Concept;
import com.bemodel.ontology.entity.Domain;
import com.bemodel.ontology.entity.Relation;
import com.bemodel.ontology.service.ConceptService;
import com.bemodel.ontology.service.DomainService;
import com.bemodel.ontology.service.MetricService;
import com.bemodel.ontology.service.RelationService;
import com.bemodel.modeling.entity.Rule;
import com.bemodel.ontology.entity.Metric;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 架构全貌组装 golden（纯逻辑，零 Spring 零 DB）：RELATION 边公理字段 + 同 (from,to) 折叠。
 * 夹具即契约：边 JSON 形状是前端 axiomEdge.js 消费的唯一数据源（spec 2026-09-21 §4/§5）。
 */
class ArchitectureServiceTest {

    private static Domain domain(String code, String name) {
        Domain d = new Domain();
        d.setCode(code);
        d.setName(name);
        return d;
    }

    private static Concept concept(String code, String name) {
        Concept c = new Concept();
        c.setCode(code);
        c.setName(name);
        c.setDomainCode("DEMO");
        return c;
    }

    private static Relation rel(String from, String to, String name, Integer symmetric, Integer transitive,
                                Integer functional, Integer inverseFunctional, Integer asymmetric, String inverseOf) {
        Relation r = new Relation();
        r.setFromConcept(from);
        r.setToConcept(to);
        r.setRelationName(name);
        r.setIsSymmetric(symmetric);
        r.setIsTransitive(transitive);
        r.setIsFunctional(functional);
        r.setIsInverseFunctional(inverseFunctional);
        r.setIsAsymmetric(asymmetric);
        r.setInverseOf(inverseOf);
        return r;
    }

    private ArchitectureService serviceWith(List<Relation> relations) {
        DomainService domainService = mock(DomainService.class);
        ConceptService conceptService = mock(ConceptService.class);
        RelationService relationService = mock(RelationService.class);
        DatasourceService datasourceService = mock(DatasourceService.class);
        SchemaScanService schemaScanService = mock(SchemaScanService.class);
        MappingService mappingService = mock(MappingService.class);
        RuleService ruleService = mock(RuleService.class);
        MetricService metricService = mock(MetricService.class);
        when(domainService.listAll()).thenReturn(List.of(domain("DEMO", "演示域")));
        when(conceptService.list()).thenReturn(List.of(concept("A", "甲"), concept("B", "乙"), concept("C", "丙")));
        when(relationService.list()).thenReturn(relations);
        when(datasourceService.listAll()).thenReturn(List.of());
        when(schemaScanService.tables(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.<PhysicalTable>of());
        when(mappingService.activeList()).thenReturn(List.of());
        when(ruleService.list()).thenReturn(List.<Rule>of());
        when(metricService.list()).thenReturn(List.<Metric>of());
        return new ArchitectureService(domainService, conceptService, relationService, datasourceService,
                schemaScanService, mappingService, ruleService, metricService);
    }

    @Test
    void relationEdgesCarryAxiomsAndFoldByPair() {
        ArchitectureService service = serviceWith(List.of(
                // 同 (A,B) 两条关系 → 折叠一条；其中「属于」带传递
                rel("A", "B", "驱动执行", 0, 0, 0, 0, 0, null),
                rel("A", "B", "属于", 0, 1, 0, 0, 0, null),
                // 对称 + 互逆声明
                rel("B", "C", "对应报告", 1, 0, 0, 0, 0, "报告归属"),
                // 低频约束公理合并进同一对
                rel("C", "A", "产生费用", 0, 0, 1, 1, 1, null),
                // 悬空引用：E 不是概念 → 不出边
                rel("A", "E", "悬空", 0, 0, 0, 0, 0, null)));
        Map<String, Object> out = service.overview();

        List<Map<String, Object>> relationEdges = ((List<?>) out.get("edges")).stream()
                .map(e -> (Map<String, Object>) e)
                .filter(e -> "RELATION".equals(e.get("kind")))
                .toList();
        // A→E 悬空不出边；同对折叠 → 恰 3 条 RELATION 边
        assertEquals(3, relationEdges.size());

        Map<String, Object> ab = relationEdges.stream()
                .filter(e -> "C:A".equals(e.get("source")) && "C:B".equals(e.get("target"))).findFirst().orElseThrow();
        assertEquals("属于/驱动执行", ab.get("label")); // 关系名 Java 自然序排序后「/」连接
        assertEquals(List.of("transitive"), ab.get("axioms")); // 成员并集 canonical 序
        assertFalse(ab.containsKey("inverseOf")); // 无互逆声明则无此键

        Map<String, Object> bc = relationEdges.stream()
                .filter(e -> "C:B".equals(e.get("source")) && "C:C".equals(e.get("target"))).findFirst().orElseThrow();
        assertEquals("对应报告", bc.get("label"));
        assertEquals(List.of("symmetric"), bc.get("axioms"));
        assertEquals("报告归属", bc.get("inverseOf"));

        Map<String, Object> ca = relationEdges.stream()
                .filter(e -> "C:C".equals(e.get("source")) && "C:A".equals(e.get("target"))).findFirst().orElseThrow();
        assertEquals(List.of("functional", "inverseFunctional", "asymmetric"), ca.get("axioms"));

        // 节点不因折叠增减：1 域 + 3 概念
        assertEquals(4, ((List<?>) out.get("nodes")).size());
    }

    @Test
    void foldTruncatesLabelBeyondThreeRelations() {
        ArchitectureService service = serviceWith(List.of(
                rel("A", "B", "丁", 0, 0, 0, 0, 0, null),
                rel("A", "B", "丙", 0, 0, 0, 0, 0, null),
                rel("A", "B", "乙", 0, 0, 0, 0, 0, null),
                rel("A", "B", "甲", 0, 0, 0, 0, 0, null)));
        Map<String, Object> out = service.overview();
        Map<String, Object> ab = ((List<?>) out.get("edges")).stream()
                .map(e -> (Map<String, Object>) e)
                .filter(e -> "RELATION".equals(e.get("kind")))
                .findFirst().orElseThrow();
        assertEquals("丁/丙/乙+1", ab.get("label")); // Java String 自然序（UTF-16 码元）：丁U+4E01<丙U+4E19<乙U+4E59<甲U+7532，取前 3 + 剩余计数
    }
}
