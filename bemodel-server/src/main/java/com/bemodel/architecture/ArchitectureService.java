package com.bemodel.architecture;

import com.bemodel.datasource.entity.Datasource;
import com.bemodel.datasource.entity.Mapping;
import com.bemodel.datasource.entity.PhysicalTable;
import com.bemodel.datasource.service.DatasourceService;
import com.bemodel.datasource.service.MappingService;
import com.bemodel.datasource.service.SchemaScanService;
import com.bemodel.modeling.entity.Rule;
import com.bemodel.modeling.service.RuleService;
import com.bemodel.ontology.entity.Concept;
import com.bemodel.ontology.entity.Domain;
import com.bemodel.ontology.entity.Metric;
import com.bemodel.ontology.entity.Relation;
import com.bemodel.ontology.service.ConceptService;
import com.bemodel.ontology.service.DomainService;
import com.bemodel.ontology.service.MetricService;
import com.bemodel.ontology.service.RelationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 架构全貌聚合（语义层可视化的数据底座）：域-概念-数据源-物理表-规则-指标一张图。
 * 只读内存聚合：概念/表数量级小，全部复用现有 Service 的查询结果，不重复写 SQL。
 * 边只挂已存在的两端节点（ dangling 引用跳过，保证前端渲染不炸）。
 */
@Service
@RequiredArgsConstructor
public class ArchitectureService {

    private final DomainService domainService;
    private final ConceptService conceptService;
    private final RelationService relationService;
    private final DatasourceService datasourceService;
    private final SchemaScanService schemaScanService;
    private final MappingService mappingService;
    private final RuleService ruleService;
    private final MetricService metricService;

    public Map<String, Object> overview() {
        List<Domain> domains = domainService.listAll();
        List<Concept> concepts = conceptService.list();
        List<Relation> relations = relationService.list();
        List<Datasource> datasources = datasourceService.listAll();
        List<Mapping> mappings = mappingService.activeList(); // 架构总览覆盖度只算生效映射（V30）
        List<Rule> rules = ruleService.list();
        List<Metric> metrics = metricService.list();

        Map<String, PhysicalTable> tables = new LinkedHashMap<>();
        for (Datasource ds : datasources) {
            for (PhysicalTable t : schemaScanService.tables(ds.getDsCode())) {
                tables.put(ds.getDsCode() + "." + t.getTableName(), t);
            }
        }

        // 概念 → 映射覆盖度（distinct 物理表数）
        Map<String, Set<String>> tablesByConcept = new HashMap<>();
        for (Mapping m : mappings) {
            tablesByConcept.computeIfAbsent(m.getConceptCode(), k -> new HashSet<>())
                    .add(m.getDsCode() + "." + m.getTableName());
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        Set<String> nodeIds = new HashSet<>();
        for (Domain d : domains) {
            addNode(nodes, nodeIds, "D:" + d.getCode(), "DOMAIN", d.getCode(), d.getName(), null, null);
        }
        for (Concept c : concepts) {
            addNode(nodes, nodeIds, "C:" + c.getCode(), "CONCEPT", c.getCode(), c.getName(), c.getDomainCode(),
                    Map.of("mappingCount", tablesByConcept.getOrDefault(c.getCode(), Set.of()).size()));
        }
        for (Datasource ds : datasources) {
            addNode(nodes, nodeIds, "DS:" + ds.getDsCode(), "DATASOURCE", ds.getDsCode(), ds.getDsName(), null, null);
        }
        for (PhysicalTable t : tables.values()) {
            String comment = t.getTableComment();
            addNode(nodes, nodeIds, "T:" + t.getDsCode() + "." + t.getTableName(), "TABLE",
                    t.getTableName(), comment == null || comment.isBlank() ? t.getTableName() : comment,
                    null, null);
        }
        for (Rule r : rules) {
            addNode(nodes, nodeIds, "R:" + r.getRuleCode(), "RULE", r.getRuleCode(), r.getName(), null, null);
        }
        for (Metric m : metrics) {
            addNode(nodes, nodeIds, "M:" + m.getMetricCode(), "METRIC", m.getMetricCode(), m.getName(), null, null);
        }

        List<Map<String, Object>> edges = new ArrayList<>();
        Set<String> edgeIds = new HashSet<>();
        for (Concept c : concepts) {
            addEdge(edges, edgeIds, nodeIds, "BELONG", "D:" + c.getDomainCode(), "C:" + c.getCode(), null);
        }
        // RELATION 边：同 (from,to) 折叠为一条（spec §5），公理随边透出（spec §4）
        Map<String, List<Relation>> relationsByPair = new LinkedHashMap<>();
        for (Relation r : relations) {
            relationsByPair.computeIfAbsent(r.getFromConcept() + "->" + r.getToConcept(),
                    k -> new ArrayList<>()).add(r);
        }
        for (Map.Entry<String, List<Relation>> en : relationsByPair.entrySet()) {
            String[] pair = en.getKey().split("->", 2);
            addRelationEdge(edges, edgeIds, nodeIds, "C:" + pair[0], "C:" + pair[1], en.getValue());
        }
        // 映射按 概念→物理表 去重（一表多列只画一条边）
        Set<String> seenMapping = new LinkedHashSet<>();
        for (Mapping m : mappings) {
            String tableId = "T:" + m.getDsCode() + "." + m.getTableName();
            if (seenMapping.add(m.getConceptCode() + "->" + tableId)) {
                addEdge(edges, edgeIds, nodeIds, "MAPPING", "C:" + m.getConceptCode(), tableId, null);
            }
        }
        for (Rule r : rules) {
            addEdge(edges, edgeIds, nodeIds, "RULE_BIND", "C:" + r.getConceptCode(), "R:" + r.getRuleCode(), null);
        }
        for (Metric m : metrics) {
            if (m.getConceptCode() != null && !m.getConceptCode().isBlank()) {
                addEdge(edges, edgeIds, nodeIds, "METRIC_BIND", "C:" + m.getConceptCode(), "M:" + m.getMetricCode(), null);
            }
        }
        for (PhysicalTable t : tables.values()) {
            addEdge(edges, edgeIds, nodeIds, "DS_TABLE",
                    "DS:" + t.getDsCode(), "T:" + t.getDsCode() + "." + t.getTableName(), null);
        }

        nodes.sort(Comparator.comparing((Map<String, Object> n) -> String.valueOf(n.get("type")))
                .thenComparing(n -> String.valueOf(n.get("id"))));
        edges.sort(Comparator.comparing((Map<String, Object> e) -> String.valueOf(e.get("kind")))
                .thenComparing(e -> String.valueOf(e.get("id"))));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", nodes);
        out.put("edges", edges);
        return out;
    }

    private void addNode(List<Map<String, Object>> nodes, Set<String> nodeIds,
                         String id, String type, String code, String name,
                         String domainCode, Map<String, Object> stats) {
        if (!nodeIds.add(id)) {
            return;
        }
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", type);
        node.put("code", code);
        node.put("name", name);
        if (domainCode != null) {
            node.put("domainCode", domainCode);
        }
        if (stats != null) {
            node.put("stats", stats);
        }
        nodes.add(node);
    }

    private void addEdge(List<Map<String, Object>> edges, Set<String> edgeIds, Set<String> nodeIds,
                         String kind, String source, String target, String label) {
        if (!nodeIds.contains(source) || !nodeIds.contains(target)) {
            return; //  dangling 引用不出边
        }
        String id = kind + "|" + source + "->" + target + (label == null ? "" : "|" + label);
        if (!edgeIds.add(id)) {
            return;
        }
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("id", id);
        edge.put("source", source);
        edge.put("target", target);
        if (label != null) {
            edge.put("label", label);
        }
        edge.put("kind", kind);
        edges.add(edge);
    }

    /** axiom canonical 顺序（与前端 axiomEdge.js AXIOM_ORDER 一致，golden 断言依赖此序） */
    private static final List<String> AXIOM_ORDER =
            List.of("symmetric", "transitive", "functional", "inverseFunctional", "asymmetric");

    private static boolean flagOn(Integer v) {
        return v != null && v == 1;
    }

    /** 同 (from,to) 成员折叠为一条 RELATION 边（spec §5；前端镜像=bemodel-web/src/utils/axiomEdge.js foldRelations，两处改动必须同步）：
     * label 排序「/」连接超 3 截断，axioms 并集 */
    private void addRelationEdge(List<Map<String, Object>> edges, Set<String> edgeIds, Set<String> nodeIds,
                                 String source, String target, List<Relation> members) {
        List<Relation> sorted = members.stream()
                .sorted(Comparator.comparing(Relation::getRelationName)).toList();
        List<String> names = sorted.stream().map(Relation::getRelationName).toList();
        String label = names.size() <= 3
                ? String.join("/", names)
                : String.join("/", names.subList(0, 3)) + "+" + (names.size() - 3);
        Set<String> axioms = new LinkedHashSet<>();
        String inverseOf = null;
        for (Relation r : sorted) {
            if (flagOn(r.getIsSymmetric())) axioms.add("symmetric");
            if (flagOn(r.getIsTransitive())) axioms.add("transitive");
            if (flagOn(r.getIsFunctional())) axioms.add("functional");
            if (flagOn(r.getIsInverseFunctional())) axioms.add("inverseFunctional");
            if (flagOn(r.getIsAsymmetric())) axioms.add("asymmetric");
            if (inverseOf == null && r.getInverseOf() != null && !r.getInverseOf().isBlank()) {
                inverseOf = r.getInverseOf();
            }
        }
        List<String> ordered = AXIOM_ORDER.stream().filter(axioms::contains).toList();
        if (!nodeIds.contains(source) || !nodeIds.contains(target)) {
            return; // dangling 引用不出边
        }
        String id = "RELATION" + "|" + source + "->" + target + "|" + label;
        if (!edgeIds.add(id)) {
            return;
        }
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("id", id);
        edge.put("source", source);
        edge.put("target", target);
        edge.put("label", label);
        edge.put("kind", "RELATION");
        if (!ordered.isEmpty()) {
            edge.put("axioms", ordered);
        }
        if (inverseOf != null) {
            edge.put("inverseOf", inverseOf);
        }
        edges.add(edge);
    }
}
