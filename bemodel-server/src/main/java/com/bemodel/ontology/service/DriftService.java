package com.bemodel.ontology.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.bemodel.modeling.entity.Release;
import com.bemodel.modeling.mapper.ReleaseMapper;
import com.bemodel.ontology.entity.Term;
import com.bemodel.ontology.mapper.ConceptMapper;
import com.bemodel.ontology.mapper.TermMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 语义漂移检测（按需扫描，不落库）：
 * 1. 术语分叉——同一说法在不同产品/编码体系下挂到不同概念（bm_term 同 term 不同 concept_code），
 *    即「同一个词在各系统里指的不是同一件事」，是口径分歧的直接证据；
 * 2. 口径演进——相邻发布版本快照间，概念定义 / 指标口径（定义+公式）的字段级变更记录。
 * 只报告真实数据，不做任何占比推断。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DriftService {

    private final TermMapper termMapper;
    private final ConceptMapper conceptMapper;
    private final ReleaseMapper releaseMapper;
    private final ObjectMapper objectMapper;

    public Map<String, Object> scan() {
        List<Map<String, Object>> termConflicts = scanTermConflicts();
        List<Map<String, Object>> calibreChanges = scanCalibreChanges();
        int termTotal = termMapper.selectCount(null).intValue();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("termConflicts", termConflicts);
        result.put("calibreChanges", calibreChanges);
        result.put("stats", Map.of(
                "termConflictCount", termConflicts.size(),
                "calibreChangeCount", calibreChanges.size(),
                "scannedTerms", termTotal,
                "scannedReleases", releaseMapper.selectCount(null).intValue()));
        return result;
    }

    /** 同一说法 → 多个概念：按术语原文分组，出现概念数大于 1 即为分叉 */
    private List<Map<String, Object>> scanTermConflicts() {
        List<Term> terms = termMapper.selectList(null);
        Map<String, String> conceptName = new LinkedHashMap<>();
        conceptMapper.selectList(null).forEach(c -> conceptName.put(c.getCode(), c.getName()));

        // term -> conceptCode -> usages
        Map<String, Map<String, List<Map<String, Object>>>> grouped = new LinkedHashMap<>();
        for (Term t : terms) {
            if (t.getTerm() == null || t.getConceptCode() == null) {
                continue;
            }
            Map<String, Object> usage = new LinkedHashMap<>();
            usage.put("conceptCode", t.getConceptCode());
            usage.put("conceptName", conceptName.getOrDefault(t.getConceptCode(), t.getConceptCode()));
            usage.put("sourceProduct", t.getSourceProduct() == null ? "-" : t.getSourceProduct());
            usage.put("termType", t.getTermType() == null ? "-" : t.getTermType());
            usage.put("codeSystem", t.getCodeSystem() == null ? "" : t.getCodeSystem());
            grouped.computeIfAbsent(t.getTerm(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(t.getConceptCode(), k -> new ArrayList<>())
                    .add(usage);
        }

        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<Map<String, Object>>>> e : grouped.entrySet()) {
            if (e.getValue().size() < 2) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("term", e.getKey());
            item.put("conceptCount", e.getValue().size());
            List<Map<String, Object>> usages = new ArrayList<>();
            e.getValue().values().forEach(usages::addAll);
            item.put("usages", usages);
            conflicts.add(item);
        }
        conflicts.sort(Comparator.comparingInt((Map<String, Object> m) -> (Integer) m.get("conceptCount")).reversed());
        return conflicts;
    }

    /** 口径演进：相邻发布版本快照间 概念定义 / 指标定义+公式 的字段级变更 */
    private List<Map<String, Object>> scanCalibreChanges() {
        List<Release> releases = releaseMapper.selectList(null);
        releases.sort(Comparator.comparingLong(Release::getId));
        List<Map<String, Object>> changes = new ArrayList<>();
        for (int i = 1; i < releases.size(); i++) {
            Release from = releases.get(i - 1);
            Release to = releases.get(i);
            Map<String, JsonNode> fromConcepts = snapshotIndex(from, "concepts", "code");
            Map<String, JsonNode> toConcepts = snapshotIndex(to, "concepts", "code");
            Map<String, JsonNode> fromMetrics = snapshotIndex(from, "metrics", "metricCode");
            Map<String, JsonNode> toMetrics = snapshotIndex(to, "metrics", "metricCode");

            for (Map.Entry<String, JsonNode> e : toConcepts.entrySet()) {
                JsonNode prev = fromConcepts.get(e.getKey());
                JsonNode cur = e.getValue();
                if (prev != null && !text(prev.get("definition")).equals(text(cur.get("definition")))) {
                    changes.add(change(to, "概念", text(cur.get("code")), text(cur.get("name")),
                            "口径定义", text(prev.get("definition")), text(cur.get("definition"))));
                }
            }
            for (Map.Entry<String, JsonNode> e : toMetrics.entrySet()) {
                JsonNode prev = fromMetrics.get(e.getKey());
                JsonNode cur = e.getValue();
                if (prev == null) {
                    continue;
                }
                if (!text(prev.get("definition")).equals(text(cur.get("definition")))) {
                    changes.add(change(to, "指标", text(cur.get("metricCode")), text(cur.get("name")),
                            "口径定义", text(prev.get("definition")), text(cur.get("definition"))));
                }
                if (!text(prev.get("formula")).equals(text(cur.get("formula")))) {
                    changes.add(change(to, "指标", text(cur.get("metricCode")), text(cur.get("name")),
                            "计算公式", text(prev.get("formula")), text(cur.get("formula"))));
                }
            }
        }
        changes.sort(Comparator.comparingLong((Map<String, Object> m) -> (Long) m.get("toReleaseId")).reversed());
        return changes;
    }

    /** 快照里某类元素按 id 字段建索引 */
    private Map<String, JsonNode> snapshotIndex(Release release, String key, String idField) {
        Map<String, JsonNode> index = new LinkedHashMap<>();
        try {
            JsonNode node = objectMapper.readTree(release.getSnapshotJson() == null ? "{}" : release.getSnapshotJson());
            for (JsonNode el : node.path(key)) {
                String id = text(el.get(idField));
                if (!id.isEmpty()) {
                    index.put(id, el);
                }
            }
        } catch (Exception e) {
            log.warn("解析发布快照失败 versionTag={} : {}", release.getVersionTag(), e.getMessage());
        }
        return index;
    }

    private Map<String, Object> change(Release to, String kind, String code, String name,
                                       String field, String oldVal, String newVal) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("toReleaseId", to.getId());
        m.put("versionTag", to.getVersionTag());
        m.put("releasedAt", to.getCreatedAt() == null ? "" : to.getCreatedAt().toString());
        m.put("kind", kind);
        m.put("code", code);
        m.put("name", name == null || name.isEmpty() ? code : name);
        m.put("field", field);
        m.put("oldVal", oldVal);
        m.put("newVal", newVal);
        return m;
    }

    private String text(JsonNode n) {
        return n == null || n.isNull() ? "" : n.asText();
    }
}
