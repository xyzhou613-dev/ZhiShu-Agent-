package com.bemodel.ontology.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bemodel.datasource.entity.Mapping;
import com.bemodel.datasource.mapper.MappingMapper;
import com.bemodel.ontology.entity.Attribute;
import com.bemodel.ontology.entity.Concept;
import com.bemodel.ontology.entity.ConceptParent;
import com.bemodel.ontology.entity.Disjoint;
import com.bemodel.ontology.entity.Relation;
import com.bemodel.ontology.mapper.AttributeMapper;
import com.bemodel.ontology.mapper.ConceptMapper;
import com.bemodel.ontology.mapper.ConceptParentMapper;
import com.bemodel.ontology.mapper.DisjointMapper;
import com.bemodel.ontology.mapper.RelationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 本体自检：发布前的结构公理一致性检查（借鉴 Utopia 的 lint 先于 release）。
 * 缺陷确定性输出：按 type + refs 排序，同一数据多次检查结果完全一致。
 */
@Service
@RequiredArgsConstructor
public class OntologyCheckService {

    private final RelationMapper relationMapper;
    private final ConceptMapper conceptMapper;
    private final DisjointMapper disjointMapper;
    private final ConceptParentMapper conceptParentMapper;
    private final AttributeMapper attributeMapper;
    private final MappingMapper mappingMapper;

    public record Defect(String type, String severity, String message, List<String> refs) {
    }

    public List<Defect> check() {
        List<Defect> defects = new ArrayList<>();
        List<Relation> relations = relationMapper.selectList(null);
        List<Concept> concepts = conceptMapper.selectList(null);
        List<Disjoint> disjoints = disjointMapper.selectList(null);
        List<ConceptParent> parents = conceptParentMapper.selectList(null);

        checkRelationAxioms(relations, defects);
        checkDisjoint(disjoints, concepts, defects);
        checkIriDuplicate(concepts, defects);
        checkSubClassCycle(parents, defects);
        // D1 属性覆盖门禁：已发布概念的属性定义与生效映射覆盖（试点教训固化）
        List<Attribute> attributes = attributeMapper.selectList(null);
        List<Mapping> activeMappings = mappingMapper.selectList(
                new LambdaQueryWrapper<Mapping>().eq(Mapping::getStatus, "ACTIVE"));
        checkAttributeCoverage(concepts, attributes, activeMappings, defects);

        defects.sort(Comparator.comparing(Defect::type)
                .thenComparing(d -> String.join(",", d.refs())));
        return defects;
    }

    public List<Defect> blockers(List<Defect> defects) {
        return defects.stream().filter(d -> "BLOCKER".equals(d.severity())).toList();
    }

    public List<Defect> warns(List<Defect> defects) {
        return defects.stream().filter(d -> "WARN".equals(d.severity())).toList();
    }

    /** 缺陷列表的报错文案（发布拦截时携带） */
    public String describe(List<Defect> defects) {
        return defects.stream()
                .map(d -> String.format("[%s/%s] %s %s", d.type(), d.severity(), d.message(), d.refs()))
                .collect(Collectors.joining("；"));
    }

    // ---------- 关系公理 ----------

    private void checkRelationAxioms(List<Relation> relations, List<Defect> defects) {
        Map<String, List<Relation>> byName = relations.stream()
                .collect(Collectors.groupingBy(Relation::getRelationName));
        for (Relation r : relations) {
            String ref = ref(r);
            if (isOne(r.getIsSymmetric()) && isOne(r.getIsAsymmetric())) {
                defects.add(new Defect("SYMMETRIC_ASYMMETRIC", "BLOCKER",
                        "关系同时声明了对称与反对称公理", List.of(ref)));
            }
            if (isOne(r.getIsTransitive()) && isOne(r.getIsFunctional())) {
                defects.add(new Defect("TRANSITIVE_FUNCTIONAL", "WARN",
                        "关系同时声明了传递与函数公理（传递闭包与唯一值语义冲突）", List.of(ref)));
            }
            String inverseOf = r.getInverseOf();
            if (inverseOf != null && !inverseOf.isBlank()) {
                if (inverseOf.equals(r.getRelationName())) {
                    defects.add(new Defect("INVERSE_SELF", "BLOCKER",
                            "关系的互逆关系指向自身", List.of(ref)));
                } else {
                    Relation inverse = resolveInverse(r, byName.getOrDefault(inverseOf, List.of()));
                    if (inverse == null) {
                        defects.add(new Defect("INVERSE_NOT_MUTUAL", "WARN",
                                "inverse_of 指向的关系不存在: " + inverseOf, List.of(ref)));
                    } else if (!r.getRelationName().equals(inverse.getInverseOf())) {
                        defects.add(new Defect("INVERSE_NOT_MUTUAL", "WARN",
                                "互逆未成对声明: " + inverseOf + " 的 inverse_of=" + inverse.getInverseOf(),
                                List.of(ref, ref(inverse))));
                    }
                }
            }
        }
    }

    /** 解析互逆关系：优先端点对调的同名关系，退化为任意同名关系 */
    private Relation resolveInverse(Relation r, List<Relation> candidates) {
        return candidates.stream()
                .filter(c -> r.getFromConcept().equals(c.getToConcept())
                        && r.getToConcept().equals(c.getFromConcept()))
                .findFirst()
                .orElse(candidates.stream().findFirst().orElse(null));
    }

    // ---------- 概念互斥 ----------

    private void checkDisjoint(List<Disjoint> disjoints, List<Concept> concepts, List<Defect> defects) {
        Set<String> conceptCodes = concepts.stream().map(Concept::getCode).collect(Collectors.toSet());
        for (Disjoint d : disjoints) {
            List<String> pair = List.of(d.getConceptACode(), d.getConceptBCode());
            if (d.getConceptACode().equals(d.getConceptBCode())) {
                defects.add(new Defect("DISJOINT_SELF", "BLOCKER",
                        "互斥行两端是同一概念", pair));
                continue;
            }
            List<String> missing = pair.stream().filter(c -> !conceptCodes.contains(c)).toList();
            if (!missing.isEmpty()) {
                defects.add(new Defect("DISJOINT_DANGLING", "WARN",
                        "互斥行引用了不存在的概念: " + String.join("、", missing), pair));
            }
        }
    }

    // ---------- IRI ----------

    // ---------- 继承环 ----------

    /**
     * SUBCLASS_CYCLE（BLOCKER）：bm_concept_parent 全图找环。
     * 三色 DFS；环规范化（从最小 code 起报告）+ 去重，保证同一数据多次检查结果一致。
     */
    private void checkSubClassCycle(List<ConceptParent> parents, List<Defect> defects) {
        Map<String, List<String>> childToParents = new LinkedHashMap<>();
        Set<String> nodes = new TreeSet<>();
        for (ConceptParent p : parents) {
            childToParents.computeIfAbsent(p.getChildCode(), k -> new ArrayList<>()).add(p.getParentCode());
            nodes.add(p.getChildCode());
            nodes.add(p.getParentCode());
        }
        Set<String> cycles = new TreeSet<>();
        Map<String, Integer> color = new LinkedHashMap<>(); // 0=未访问 1=在栈 2=完成
        for (String start : nodes) {
            if (color.getOrDefault(start, 0) == 0) {
                dfsCycle(start, childToParents, color, new ArrayList<>(), cycles);
            }
        }
        for (String cycle : cycles) {
            defects.add(new Defect("SUBCLASS_CYCLE", "BLOCKER",
                    "概念继承存在环: " + cycle, List.of(cycle.split("→"))));
        }
    }

    private void dfsCycle(String node, Map<String, List<String>> childToParents,
                          Map<String, Integer> color, List<String> stack, Set<String> cycles) {
        color.put(node, 1);
        stack.add(node);
        for (String parent : childToParents.getOrDefault(node, List.of())) {
            int c = color.getOrDefault(parent, 0);
            if (c == 1) {
                // 命中栈上节点 → 截取环段并规范化（从最小 code 起报告）
                List<String> ring = new ArrayList<>(stack.subList(stack.indexOf(parent), stack.size()));
                int minIdx = 0;
                for (int i = 1; i < ring.size(); i++) {
                    if (ring.get(i).compareTo(ring.get(minIdx)) < 0) {
                        minIdx = i;
                    }
                }
                List<String> rotated = new ArrayList<>(ring.subList(minIdx, ring.size()));
                rotated.addAll(ring.subList(0, minIdx));
                rotated.add(ring.get(minIdx));
                cycles.add(String.join("→", rotated));
            } else if (c == 0) {
                dfsCycle(parent, childToParents, color, stack, cycles);
            }
        }
        stack.remove(stack.size() - 1);
        color.put(node, 2);
    }

    // ---------- IRI ----------

    private void checkIriDuplicate(List<Concept> concepts, List<Defect> defects) {
        Map<String, List<String>> byIri = new LinkedHashMap<>();
        for (Concept c : concepts) {
            if (c.getIri() != null && !c.getIri().isBlank()) {
                byIri.computeIfAbsent(c.getIri(), k -> new ArrayList<>()).add(c.getCode());
            }
        }
        for (Map.Entry<String, List<String>> e : byIri.entrySet()) {
            if (e.getValue().size() > 1) {
                List<String> codes = e.getValue().stream().sorted().toList();
                defects.add(new Defect("IRI_DUPLICATE", "BLOCKER",
                        "多个概念共用同一 IRI: " + e.getKey(), codes));
            }
        }
    }

    // ---------- 属性覆盖门禁（D1，试点教训固化） ----------

    /**
     * 发布口径的两个覆盖缺口（WARN 级，配合发布门禁：不自检清零就须 force 显式放行）：
     * 1) CONCEPT_NO_ATTR：已发布概念没有任何属性定义——裸概念，问数/投影无字段可还原；
     * 2) ATTR_MAPPING_COVERAGE：已发布概念的属性没有生效映射承载——实例投影/语义问数在这些属性上
     *    静默缺失（试点教训：mo_lis 试点中"概念已发布、物理列未绑定"曾导致问数答案静默缺列）。
     */
    private void checkAttributeCoverage(List<Concept> concepts, List<Attribute> attributes,
                                        List<Mapping> activeMappings, List<Defect> defects) {
        Map<String, Set<String>> mappedAttrByConcept = activeMappings.stream()
                .collect(Collectors.groupingBy(Mapping::getConceptCode,
                        Collectors.mapping(Mapping::getAttrCode, Collectors.toSet())));
        Map<String, Set<String>> attrsByConcept = attributes.stream()
                .collect(Collectors.groupingBy(Attribute::getConceptCode,
                        Collectors.mapping(Attribute::getAttrCode, Collectors.toSet())));
        List<Concept> published = concepts.stream()
                .filter(c -> "PUBLISHED".equals(c.getStatus()))
                .sorted(Comparator.comparing(Concept::getCode)).toList();
        for (Concept c : published) {
            Set<String> attrs = attrsByConcept.getOrDefault(c.getCode(), Set.of());
            if (attrs.isEmpty()) {
                defects.add(new Defect("CONCEPT_NO_ATTR", "WARN",
                        "已发布概念没有任何属性定义（裸概念），下游无字段可消费", List.of(c.getCode())));
                continue;
            }
            Set<String> mapped = mappedAttrByConcept.getOrDefault(c.getCode(), Set.of());
            List<String> missing = attrs.stream().filter(a -> !mapped.contains(a))
                    .sorted().toList();
            if (!missing.isEmpty()) {
                List<String> shown = missing.size() > 8 ? missing.subList(0, 8) : missing;
                List<String> refs = new ArrayList<>();
                refs.add(c.getCode());
                refs.addAll(shown);
                defects.add(new Defect("ATTR_MAPPING_COVERAGE", "WARN",
                        String.format("属性缺生效映射 %d/%d（发布后实例投影与问数将静默缺失）: %s%s",
                                missing.size(), attrs.size(), String.join("、", shown),
                                missing.size() > 8 ? " …" : ""),
                        refs));
            }
        }
    }

    private boolean isOne(Integer v) {
        return v != null && v == 1;
    }

    /** 关系的稳定引用（bm_relation 无独立 code 列，用唯一键表示） */
    public static String ref(Relation r) {
        return r.getFromConcept() + "->" + r.getToConcept() + "#" + r.getRelationName();
    }
}
