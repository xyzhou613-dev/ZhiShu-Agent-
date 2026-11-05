package com.bemodel.impact;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bemodel.common.BizException;
import com.bemodel.datasource.entity.Mapping;
import com.bemodel.datasource.mapper.MappingMapper;
import com.bemodel.link.entity.LinkNode;
import com.bemodel.link.mapper.LinkNodeMapper;
import com.bemodel.llm.DeepSeekClient;
import com.bemodel.ontology.entity.Concept;
import com.bemodel.ontology.entity.Metric;
import com.bemodel.ontology.entity.Relation;
import com.bemodel.ontology.mapper.ConceptMapper;
import com.bemodel.ontology.mapper.MetricMapper;
import com.bemodel.ontology.mapper.RelationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 变更影响评估：研发变更提交时，以本体概念为中枢自动评估受影响面——
 * 物理映射（哪些产品库的哪些表/列）、指标口径、测试用例覆盖、上下游概念，
 * 再由 LLM 汇总为评估意见。堵住案例中"状态字典变更仅靠口头通知"的流程漏洞。
 */
@Service
@RequiredArgsConstructor
public class ImpactService {

    private final ConceptMapper conceptMapper;
    private final MappingMapper mappingMapper;
    private final MetricMapper metricMapper;
    private final RelationMapper relationMapper;
    private final LinkNodeMapper linkNodeMapper;
    private final DeepSeekClient deepSeekClient;

    public Map<String, Object> analyze(String conceptCode, String changeDesc) {
        return analyze(conceptCode, changeDesc, null);
    }

    public Map<String, Object> analyze(String conceptCode, String changeDesc, Integer depth) {
        Concept concept = conceptMapper.selectOne(
                new LambdaQueryWrapper<Concept>().eq(Concept::getCode, conceptCode));
        if (concept == null) {
            throw new BizException("概念不存在: " + conceptCode);
        }

        // 受影响物理映射：按产品库分组
        List<Mapping> mappings = mappingMapper.selectList(
                new LambdaQueryWrapper<Mapping>().eq(Mapping::getConceptCode, conceptCode)
                        .eq(Mapping::getStatus, "ACTIVE")); // 影响面只算生效映射（V30）
        Map<String, List<String>> affectedTables = mappings.stream().collect(Collectors.groupingBy(
                Mapping::getDsCode,
                LinkedHashMap::new,
                Collectors.mapping(m -> m.getTableName() + "." + m.getColumnName()
                        + (m.getValueMap() != null ? "（含值字典 " + m.getValueMap() + "）" : ""), Collectors.toList())));

        // 受影响指标
        List<Metric> metrics = metricMapper.selectList(
                new LambdaQueryWrapper<Metric>().eq(Metric::getConceptCode, conceptCode));

        // 已有测试用例覆盖
        List<LinkNode> testcases = linkNodeMapper.selectList(new LambdaQueryWrapper<LinkNode>()
                .eq(LinkNode::getConceptCode, conceptCode).eq(LinkNode::getNodeType, "TESTCASE"));

        // 上下游概念：本体图双向 BFS 多跳影响面（默认 3 跳，上限 6；截断显式 capped）
        int maxDepth = depth == null || depth < 1 ? 3 : Math.min(depth, 6);
        List<Relation> allRelations = relationMapper.selectList(null);
        Map<String, Integer> hopOf = new LinkedHashMap<>();
        hopOf.put(conceptCode, 0);
        List<String> relatedConcepts = new ArrayList<>();
        Set<Long> seenEdges = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(conceptCode);
        boolean capped = false;
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            int hop = hopOf.get(cur);
            for (Relation r : allRelations) {
                boolean isOut = r.getFromConcept().equals(cur);
                boolean isIn = r.getToConcept().equals(cur);
                if (!isOut && !isIn) {
                    continue;
                }
                String next = isOut ? r.getToConcept() : r.getFromConcept();
                if (next.equals(conceptCode)) {
                    continue;
                }
                if (hop >= maxDepth) {
                    capped = true; // 到上限仍有未展开的邻居 → 显式截断标记
                    continue;
                }
                if (seenEdges.add(r.getId())) {
                    relatedConcepts.add(String.format("%s —%s→ %s（%s · %d跳）",
                            r.getFromConcept(), r.getRelationName(), r.getToConcept(),
                            isOut ? "下游受影响" : "上游来源", hop + 1));
                }
                if (!hopOf.containsKey(next)) {
                    hopOf.put(next, hop + 1);
                    queue.add(next);
                }
            }
        }
        // 按跳数分层（前端直接可展示）
        Map<Integer, List<String>> impactLayers = new TreeMap<>();
        hopOf.forEach((code, hop) -> {
            if (hop > 0) {
                impactLayers.computeIfAbsent(hop, k -> new ArrayList<>()).add(code);
            }
        });

        // 历史变更/工单（同类变更的历史教训）
        List<LinkNode> history = linkNodeMapper.selectList(new LambdaQueryWrapper<LinkNode>()
                .eq(LinkNode::getConceptCode, conceptCode)
                .in(LinkNode::getNodeType, "CHANGE", "TICKET")
                .orderByDesc(LinkNode::getOccurredAt));

        // LLM 汇总评估意见
        StringBuilder ctx = new StringBuilder();
        ctx.append("变更描述：").append(changeDesc).append('\n');
        ctx.append("涉及概念：").append(concept.getName()).append("（").append(conceptCode).append("）\n");
        ctx.append("受影响产品库物理映射：\n");
        affectedTables.forEach((ds, cols) -> ctx.append("- ").append(ds).append("：")
                .append(String.join("、", cols)).append('\n'));
        ctx.append("关联指标：").append(metrics.stream().map(Metric::getName).toList()).append('\n');
        ctx.append("已有测试用例：").append(testcases.stream().map(LinkNode::getTitle).toList()).append('\n');
        ctx.append("上下游概念（多跳影响面，最多" + maxDepth + "跳）：").append(relatedConcepts).append('\n');
        ctx.append("同类历史变更与工单：")
                .append(history.stream().map(h -> h.getRefNo() + " " + h.getTitle()).toList()).append('\n');

        String advice = "";
        boolean llmUsed = false;
        Optional<String> llm = deepSeekClient.chat("IMPACT_ADVICE",
                "你是医疗信息化变更评审专家。基于影响面信息输出变更评估意见：一、风险点（按严重度排序）；二、必须同步修改的下游清单；三、测试补充建议。300字以内，直接给结论。",
                ctx.toString());
        if (llm.isPresent()) {
            advice = llm.get();
            llmUsed = true;
        } else {
            advice = String.format("本次变更涉及概念「%s」，影响 %d 个产品库 %d 处物理映射（%s）、%d 个指标、%d 个已有测试用例。",
                    concept.getName(), affectedTables.size(), mappings.size(),
                    String.join(",", affectedTables.keySet()), metrics.size(), testcases.size())
                    + (testcases.isEmpty() ? "当前无测试用例覆盖，必须补充。" : "请核对测试用例是否覆盖本次变更场景。")
                    + "请逐项确认下游同步改造后再发布。";
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conceptCode", conceptCode);
        result.put("conceptName", concept.getName());
        result.put("changeDesc", changeDesc);
        result.put("affectedTables", affectedTables);
        result.put("affectedMetrics", metrics.stream().map(m -> Map.of(
                "code", m.getMetricCode(), "name", m.getName())).toList());
        result.put("coveredTestcases", testcases.stream().map(t -> Map.of(
                "refNo", t.getRefNo(), "title", t.getTitle(), "status", t.getStatus())).toList());
        result.put("relatedConcepts", relatedConcepts);
        result.put("impactLayers", impactLayers);
        result.put("impactDepth", maxDepth);
        result.put("impactCapped", capped);
        result.put("history", history.stream().map(h -> Map.of(
                "refNo", h.getRefNo(), "title", h.getTitle(), "type", h.getNodeType())).toList());
        result.put("advice", advice);
        result.put("llmUsed", llmUsed);
        return result;
    }
}
