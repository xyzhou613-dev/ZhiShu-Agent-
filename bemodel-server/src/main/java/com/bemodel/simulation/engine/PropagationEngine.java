package com.bemodel.simulation.engine;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 概念图前向传导引擎(spec 路线 B,纯逻辑零 LLM):
 * ①拓扑:bm_relation 无向扩散(影响链沿边走,首达定层级——纯"反向谁依赖我"到不了结算,
 *   而 FEE_DETAIL—汇总入→SETTLEMENT 是正向边,spec §6 镜头要求结算入镜);
 * ②落地:逐概念取 ACTIVE 映射定位表/列,按追踪键(属性中文名,同义键经 keyFamily 归一,
 *   如「项目编码」∈「药品编码」族)参数化查询;
 * ③接力:结果行按本表全部映射列收割新追踪值(跨概念映射即外键),链随值走。
 * 无可接力键(映射在但接不上追踪键族)=BLOCKED_GAP 如实断链,波及到此为止;映射在但查无实例=EMPTY,链可经其他键继续。
 */
@Component
public class PropagationEngine {

    /** 截断纪律同 lab(spec §4):hits 上限编入 SQL LIMIT,样本 5 行,摘要 200,字段值 120 */
    private static final int MAX_HITS = 50;
    private static final int SAMPLE_SIZE = 5;
    private static final int SUMMARY_MAX = 200;
    private static final int FIELD_MAX = 120;

    /** 表列名虽来自映射种子(库内受控数据),仍按标识符白名单校验——注释要说真话 */
    private static final java.util.regex.Pattern SAFE_IDENT = java.util.regex.Pattern.compile("[a-z0-9_]+");

    /** 待访问节点:concept + 到达边(真实 from/to,证据的关系链恒按真实边方向渲染) */
    private record InNode(String concept, String prev, String edgeFrom, String edgeTo, String viaRelation) {}

    public List<PropagationStep> propagate(CatalogSnapshot catalog, String startConcept,
                                           List<String> keyFamily, StepQueryExecutor executor,
                                           LinkedHashMap<String, Set<String>> startKeys) {
        List<PropagationStep> steps = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        // 追踪键→受影响值集;LinkedHashMap 保插入序,键选择确定性=先到先选
        Map<String, Set<String>> tracked = new LinkedHashMap<>();
        startKeys.forEach((k, v) -> tracked.put(k, new LinkedHashSet<>(v)));

        Deque<InNode> queue = new ArrayDeque<>();
        queue.add(new InNode(startConcept, null, null, null, null));
        int level = 0;
        while (!queue.isEmpty()) {
            List<InNode> layer = new ArrayList<>(queue);
            queue.clear();
            for (InNode node : layer) {
                if (visited.contains(node.concept())) {
                    continue;
                }
                visited.add(node.concept());
                PropagationStep step = stepFor(node, level, keyFamily, catalog, executor, tracked);
                steps.add(step);
                if (!PropagationStep.BLOCKED_GAP.equals(step.status())) {
                    for (CatalogSnapshot.Rel rel : catalog.neighbors(node.concept())) {
                        String next = rel.from().equals(node.concept()) ? rel.to() : rel.from();
                        queue.add(new InNode(next, node.concept(), rel.from(), rel.to(), rel.name()));
                    }
                }
            }
            level++;
        }
        return steps;
    }

    private PropagationStep stepFor(InNode node, int level, List<String> keyFamily,
                                    CatalogSnapshot catalog, StepQueryExecutor executor,
                                    Map<String, Set<String>> tracked) {
        String concept = node.concept();
        String conceptName = catalog.conceptName(concept);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("relationChain", node.prev() == null ? "场景动手改的起点"
                : node.edgeFrom() + " —" + node.viaRelation() + "→ " + node.edgeTo());

        // 1) 选传导键:按追踪键插入序,在该概念自己的映射列里找"属性中文名同键族"的第一把钥匙;
        //    同键多列时,按"所在表的本概念列数多者优先、表名升序破平"取主表(如 MEDICAL_ORDER 取
        //    medical_order 而非 presc_review 上那条跨概念引用)
        CatalogSnapshot.Col keyCol = null;
        String usedKey = null;
        for (Map.Entry<String, Set<String>> e : tracked.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            List<CatalogSnapshot.Col> candidates = catalog.ownCols(concept).stream()
                    .filter(c -> e.getKey().equals(familyOf(catalog.attrName(c.concept(), c.attrCode()), keyFamily)))
                    .toList();
            if (candidates.isEmpty()) {
                continue;
            }
            candidates = candidates.stream().sorted(Comparator
                    .comparingLong((CatalogSnapshot.Col c) -> -ownColCount(catalog, concept, c.table()))
                    .thenComparing(CatalogSnapshot.Col::table)).toList();
            keyCol = candidates.get(0);
            usedKey = e.getKey();
            break;
        }

        // 2) 无钥匙=已建模但映射属性接不上追踪键族:如实断链(spec 红线③),波及到此为止
        if (keyCol == null) {
            evidence.put("note", conceptName + " 已建模,但没有可接力的「" + keyFamily.get(0) + "」类键,波及到此为止");
            return new PropagationStep(concept, conceptName, level, PropagationStep.BLOCKED_GAP,
                    evidence, null, 0, List.of());
        }

        if (!SAFE_IDENT.matcher(keyCol.table()).matches() || !SAFE_IDENT.matcher(keyCol.column()).matches()) {
            throw new IllegalArgumentException("映射种子含非法标识符: " + keyCol.table() + "." + keyCol.column());
        }
        // 3) 参数化查询(值全走 ?,表列名经 [a-z0-9_] 白名单校验后才拼 SQL)
        List<Object> values = new ArrayList<>(tracked.get(usedKey));
        String placeholders = String.join(",", Collections.nCopies(values.size(), "?"));
        String sql = "SELECT * FROM " + keyCol.table() + " WHERE " + keyCol.column()
                + " IN (" + placeholders + ") LIMIT " + MAX_HITS;
        List<Map<String, Object>> rows = executor.query(sql, values);

        List<String> mappingRefs = new ArrayList<>();
        mappingRefs.add(concept + "." + keyCol.attrCode() + " → " + keyCol.table() + "." + keyCol.column());
        String actualName = catalog.attrName(keyCol.concept(), keyCol.attrCode());
        if (actualName != null && !actualName.equals(usedKey)) {
            mappingRefs.add("口径备注: 该列属性名「" + actualName + "」,按演示键族归一进「" + usedKey + "」");
        }
        evidence.put("keyUsed", usedKey + " = " + values);
        evidence.put("mappingRefs", mappingRefs);

        // 4) 收割:本表全部映射列(含跨概念引用列)的值进追踪键——外键接力,链随值走
        for (CatalogSnapshot.Col c : catalog.colsOnTable(keyCol.table())) {
            String n = catalog.attrName(c.concept(), c.attrCode());
            if (n == null) {
                continue;
            }
            tracked.computeIfAbsent(familyOf(n, keyFamily), k -> new LinkedHashSet<>());
            Set<String> bucket = tracked.get(familyOf(n, keyFamily));
            for (Map<String, Object> row : rows) {
                Object v = row.get(c.column());
                if (v != null) {
                    bucket.add(String.valueOf(v));
                }
            }
        }

        String status = rows.isEmpty() ? PropagationStep.EMPTY : PropagationStep.MATCHED;
        evidence.put("statusNote", rows.isEmpty() ? "依据在,但演示数据里查无相关实例" : "查到 " + rows.size() + " 条相关实例");
        return new PropagationStep(concept, conceptName, level, status, evidence,
                clip(sql, SUMMARY_MAX), rows.size(), sampleOf(rows));
    }

    /** 属性中文名→键族 canonical(族外自成一家) */
    private String familyOf(String attrName, List<String> keyFamily) {
        if (attrName == null) {
            return null;
        }
        return keyFamily.contains(attrName) ? keyFamily.get(0) : attrName;
    }

    private long ownColCount(CatalogSnapshot catalog, String concept, String table) {
        return catalog.ownCols(concept).stream().filter(c -> c.table().equals(table)).count();
    }

    private List<Map<String, Object>> sampleOf(List<Map<String, Object>> rows) {
        return rows.stream().limit(SAMPLE_SIZE).map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            r.forEach((k, v) -> m.put(k, v == null ? null : clip(String.valueOf(v), FIELD_MAX)));
            return m;
        }).toList();
    }

    private String clip(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }
}
