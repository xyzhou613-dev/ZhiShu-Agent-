package com.bemodel.modeling.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bemodel.auth.CurrentUser;
import com.bemodel.common.BizException;
import com.bemodel.modeling.entity.Action;
import com.bemodel.modeling.entity.Release;
import com.bemodel.modeling.entity.Rule;
import com.bemodel.modeling.mapper.ActionMapper;
import com.bemodel.modeling.mapper.ReleaseMapper;
import com.bemodel.modeling.mapper.RuleMapper;
import com.bemodel.ontology.entity.Concept;
import com.bemodel.ontology.entity.Attribute;
import com.bemodel.ontology.entity.Metric;
import com.bemodel.ontology.entity.Relation;
import com.bemodel.ontology.entity.RelationClosure;
import com.bemodel.ontology.entity.Term;
import com.bemodel.ontology.mapper.AttributeMapper;
import com.bemodel.ontology.mapper.ConceptMapper;
import com.bemodel.ontology.mapper.MetricMapper;
import com.bemodel.ontology.mapper.RelationClosureMapper;
import com.bemodel.ontology.mapper.RelationMapper;
import com.bemodel.ontology.mapper.TermMapper;
import com.bemodel.ontology.service.OntologyCheckService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 本体版本发布：把当前全部「已发布」元素打成不可变快照（版本号自动递增）。
 * LLM 调用一律绑定最新发布版本，保证提示词可复现、效果可审计。
 * 发布前先跑本体自检（借鉴 Utopia：lint 先于 release）：
 * 存在 BLOCKER 一律拒绝；仅剩 WARN 时需 force=true 放行。
 */
@Service
@RequiredArgsConstructor
public class ReleaseService extends ServiceImpl<ReleaseMapper, Release> {

    private final ConceptMapper conceptMapper;
    private final AttributeMapper attributeMapper;
    private final RelationMapper relationMapper;
    private final TermMapper termMapper;
    private final MetricMapper metricMapper;
    private final RuleMapper ruleMapper;
    private final ActionMapper actionMapper;
    private final OntologyCheckService ontologyCheckService;
    private final ObjectMapper objectMapper;
    private final RelationClosureMapper relationClosureMapper;

    /** 传递闭包深度上限：撞上即截断并在发布结果里显式报告 capped */
    private static final int CLOSURE_MAX_DEPTH = 12;

    public Release publish(String changeSummary, String releasedBy) {
        return publish(changeSummary, releasedBy, false);
    }

    /**
     * 发布事务边界：自检 + 快照读取 + 单行写入整体原子（默认隔离级下快照视图一致，写入失败整体回滚）。
     * 方法内无 LLM 等慢调用（自检为纯本地规则演算），长事务风险可控。
     */
    @Transactional
    public Release publish(String changeSummary, String releasedBy, boolean force) {
        // 评审门禁（P0②）：本体版本发布须评审员或管理员审批
        if (!CurrentUser.hasAnyRole("REVIEWER", "ADMIN")) {
            throw new BizException("本体版本发布需评审员（REVIEWER）或管理员（ADMIN）审批");
        }
        List<OntologyCheckService.Defect> defects = ontologyCheckService.check();
        List<OntologyCheckService.Defect> blockers = ontologyCheckService.blockers(defects);
        if (!blockers.isEmpty()) {
            throw new BizException("本体自检未通过，存在 " + blockers.size() + " 个阻断缺陷，禁止发布: "
                    + ontologyCheckService.describe(blockers));
        }
        if (!force && !defects.isEmpty()) {
            throw new BizException("本体自检存在 " + defects.size() + " 个警告，确认后可带 force=true 发布: "
                    + ontologyCheckService.describe(defects));
        }
        try {
            List<Concept> concepts = conceptMapper.selectList(
                    new LambdaQueryWrapper<Concept>().eq(Concept::getStatus, "PUBLISHED"));
            List<Attribute> attributes = attributeMapper.selectList(
                    new LambdaQueryWrapper<Attribute>().in(Attribute::getConceptCode,
                            concepts.stream().map(Concept::getCode).toList()));
            List<Relation> relations = relationMapper.selectList(null);
            List<Term> terms = termMapper.selectList(null);
            List<Metric> metrics = metricMapper.selectList(null);
            List<Rule> rules = ruleMapper.selectList(
                    new LambdaQueryWrapper<Rule>().eq(Rule::getStatus, "PUBLISHED"));
            List<Action> actions = actionMapper.selectList(
                    new LambdaQueryWrapper<Action>().eq(Action::getStatus, "PUBLISHED"));

            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("concepts", concepts);
            snapshot.put("attributes", attributes);
            snapshot.put("relations", relations);
            snapshot.put("terms", terms);
            snapshot.put("metrics", metrics);
            snapshot.put("rules", rules);
            snapshot.put("actions", actions);
            // 传递闭包物化（同事务）：推理在发布时，查询零推理成本
            snapshot.put("relationClosures", rebuildRelationClosures());

            Release release = new Release();
            release.setVersionTag(nextTag());
            release.setChangeSummary(changeSummary);
            release.setElementCount(concepts.size() + attributes.size() + relations.size()
                    + terms.size() + metrics.size() + rules.size() + actions.size());
            release.setSnapshotJson(objectMapper.writeValueAsString(snapshot));
            release.setReleasedBy(releasedBy);
            // 审批留痕（V29）：谁批准了这版口径，与 releasedBy 区分
            release.setApprovedBy(CurrentUser.username());
            release.setApprovedAt(LocalDateTime.now());
            save(release);
            return release;
        } catch (Exception e) {
            throw new RuntimeException("发布失败: " + e.getMessage(), e);
        }
    }

    /** 最新发布版本号；从未发布过返回 null */
    public String currentTag() {
        Release latest = getOne(new LambdaQueryWrapper<Release>()
                .orderByDesc(Release::getId).last("LIMIT 1"), false);
        return latest == null ? null : latest.getVersionTag();
    }

    /** 闭包 BFS 纯计算（golden 直测，零 IO）：depths=关系名→源→(可达概念→最短跳数)；cappedNames=超深截断的关系名 */
    record ClosureDepth(Map<String, Map<String, Map<String, Integer>>> depths, Set<String> cappedNames) {}

    static ClosureDepth closureBfs(List<Relation> all, int maxDepth) {
        Set<String> transitiveNames = new TreeSet<>();
        for (Relation r : all) {
            if (r.getIsTransitive() != null && r.getIsTransitive() == 1) {
                transitiveNames.add(r.getRelationName());
            }
        }
        Map<String, Map<String, Map<String, Integer>>> depths = new LinkedHashMap<>();
        Set<String> cappedNames = new TreeSet<>();
        for (String name : transitiveNames) {
            Map<String, List<String>> adj = new LinkedHashMap<>();
            Set<String> sources = new TreeSet<>();
            for (Relation r : all) {
                if (name.equals(r.getRelationName())) {
                    adj.computeIfAbsent(r.getFromConcept(), k -> new ArrayList<>()).add(r.getToConcept());
                    sources.add(r.getFromConcept());
                }
            }
            Map<String, Map<String, Integer>> bySource = new LinkedHashMap<>();
            boolean capped = false;
            for (String source : sources) {
                // 单源 BFS：to → 最短跳数（与抽取前逐字同源）
                Map<String, Integer> depthOf = new LinkedHashMap<>();
                Deque<String> queue = new ArrayDeque<>();
                for (String first : adj.getOrDefault(source, List.of())) {
                    if (!first.equals(source) && depthOf.putIfAbsent(first, 1) == null) {
                        queue.add(first);
                    }
                }
                while (!queue.isEmpty()) {
                    String cur = queue.poll();
                    int d = depthOf.get(cur);
                    if (d >= maxDepth) {
                        if (!adj.getOrDefault(cur, List.of()).isEmpty()) {
                            capped = true; // 深度上限截断，显式报告
                        }
                        continue;
                    }
                    for (String next : adj.getOrDefault(cur, List.of())) {
                        if (!next.equals(source) && depthOf.putIfAbsent(next, d + 1) == null) {
                            queue.add(next);
                        }
                    }
                }
                bySource.put(source, depthOf);
            }
            depths.put(name, bySource);
            if (capped) {
                cappedNames.add(name);
            }
        }
        return new ClosureDepth(depths, cappedNames);
    }

    /**
     * 传递闭包重建：对每个 is_transitive=1 的关系名，先删后插重建全部闭包行（BFS 见 closureBfs）。
     * 返回每个关系名的统计 {edges, derived, capped}，随快照落库。
     */
    private Map<String, Object> rebuildRelationClosures() {
        List<Relation> all = relationMapper.selectList(null);
        ClosureDepth cd = closureBfs(all, CLOSURE_MAX_DEPTH);
        Map<String, Object> stats = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Map<String, Integer>>> entry : cd.depths().entrySet()) {
            String name = entry.getKey();
            relationClosureMapper.delete(new LambdaQueryWrapper<RelationClosure>()
                    .eq(RelationClosure::getRelationName, name));
            int derived = 0;
            for (Map.Entry<String, Map<String, Integer>> sourceEntry : entry.getValue().entrySet()) {
                for (Map.Entry<String, Integer> e : sourceEntry.getValue().entrySet()) {
                    RelationClosure rc = new RelationClosure();
                    rc.setRelationName(name);
                    rc.setFromConcept(sourceEntry.getKey());
                    rc.setToConcept(e.getKey());
                    rc.setDepth(e.getValue());
                    relationClosureMapper.insert(rc);
                    derived++;
                }
            }
            stats.put(name, Map.of("edges", all.stream().filter(r -> name.equals(r.getRelationName())).count(),
                    "derived", derived, "capped", cd.cappedNames().contains(name)));
        }
        return stats;
    }

    /** 闭包查询：concept 经 relation 传递可达的全部概念（按跳数升序；零结果如实返回） */
    public Map<String, Object> closureOf(String relation, String concept) {
        List<RelationClosure> rows = relationClosureMapper.selectList(new LambdaQueryWrapper<RelationClosure>()
                .eq(RelationClosure::getRelationName, relation)
                .eq(RelationClosure::getFromConcept, concept)
                .orderByAsc(RelationClosure::getDepth).orderByAsc(RelationClosure::getToConcept));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("relation", relation);
        result.put("concept", concept);
        result.put("count", rows.size());
        result.put("reachable", rows.stream().map(r -> Map.of(
                "concept", r.getToConcept(), "depth", r.getDepth())).toList());
        return result;
    }

    private String nextTag() {
        String current = currentTag();
        if (current == null) {
            return "v1.0";
        }
        try {
            String[] parts = current.substring(1).split("\\.");
            return "v" + parts[0] + "." + (Integer.parseInt(parts[1]) + 1);
        } catch (Exception e) {
            return current + ".1";
        }
    }

    public List<Release> listAll() {
        return list(new LambdaQueryWrapper<Release>().orderByDesc(Release::getId)
                .select(Release.class, f -> !f.getColumn().equals("snapshot_json")));
    }
}
