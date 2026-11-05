package com.bemodel.governance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bemodel.common.BizException;
import com.bemodel.common.Masking;
import com.bemodel.common.PageResult;
import com.bemodel.datasource.entity.Mapping;
import com.bemodel.datasource.entity.PhysicalColumn;
import com.bemodel.datasource.entity.PhysicalTable;
import com.bemodel.datasource.mapper.MappingMapper;
import com.bemodel.datasource.mapper.PhysicalColumnMapper;
import com.bemodel.datasource.mapper.PhysicalTableMapper;
import com.bemodel.datasource.service.DatasourceService;
import com.bemodel.governance.mapper.GovIssueMapper;
import com.bemodel.governance.mapper.GovRuleMapper;
import com.bemodel.governance.mapper.GovScanMapper;
import com.bemodel.link.entity.LinkNode;
import com.bemodel.link.service.LinkService;
import com.bemodel.ontology.entity.Concept;
import com.bemodel.ontology.mapper.ConceptMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 数据治理：治理规则挂在本体概念上（bm_gov_rule），经映射/表达式编译为真实 SQL 探针执行。
 * 治理不是另起炉灶——规则、字典、参照关系全部来自本体层，扫描结果（问题）即 DATA_ISSUE 概念的实例。
 */
@Service
@RequiredArgsConstructor
public class GovService {

    private final GovRuleMapper govRuleMapper;
    private final GovIssueMapper govIssueMapper;
    private final GovScanMapper govScanMapper;
    private final PhysicalTableMapper physicalTableMapper;
    private final PhysicalColumnMapper physicalColumnMapper;
    private final MappingMapper mappingMapper;
    private final ConceptMapper conceptMapper;
    private final DatasourceService datasourceService;
    private final LinkService linkService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Map<String, Integer> SEVERITY_WEIGHT = Map.of("高", 3, "中", 2, "低", 1);

    // ---------- 扫描执行 ----------

    /** 执行全量治理扫描：每条规则编译为 SQL 探针真实执行，问题落库，返回扫描概要 */
    public Map<String, Object> scan() {
        long t0 = System.currentTimeMillis();
        List<GovRule> rules = govRuleMapper.selectList(
                new LambdaQueryWrapper<GovRule>().eq(GovRule::getStatus, "PUBLISHED"));

        List<GovIssue> issues = new ArrayList<>();
        int passedWeight = 0;
        int totalWeight = 0;
        for (GovRule rule : rules) {
            int w = SEVERITY_WEIGHT.getOrDefault(rule.getSeverity(), 1);
            totalWeight += w;
            List<GovIssue> found = probe(rule);
            if (found.isEmpty()) {
                passedWeight += w;
            }
            issues.addAll(found);
        }

        long duration = System.currentTimeMillis() - t0;
        int score = totalWeight == 0 ? 100 : Math.round(100f * passedWeight / totalWeight);

        // 扫描记录
        GovScan scan = new GovScan();
        scan.setScanTime(LocalDateTime.now());
        scan.setDurationMs(duration);
        scan.setRuleCount(rules.size());
        scan.setIssueCount(issues.size());
        scan.setQualityScore(score);
        govScanMapper.insert(scan);
        Long scanId = scan.getId();

        for (GovIssue issue : issues) {
            issue.setScanId(scanId);
            issue.setStatus("未处理");
            govIssueMapper.insert(issue);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scanId", scanId);
        result.put("durationMs", duration);
        result.put("ruleCount", rules.size());
        result.put("issueCount", issues.size());
        result.put("qualityScore", score);
        return result;
    }

    /** 单条规则编译执行：返回发现的问题（0 或 1 个，命中行数聚合在 hitCount） */
    private List<GovIssue> probe(GovRule rule) {
        try {
            JsonNode expr = objectMapper.readTree(rule.getExprJson());
            return switch (rule.getRuleType()) {
                case "PK_UNIQUE" -> probePkUnique(rule, expr);
                case "NOT_NULL" -> probeNotNull(rule, expr);
                case "DICT_CONSISTENT" -> probeDictConsistent(rule, expr);
                case "REF_INTACT" -> probeRefIntact(rule, expr);
                case "STOCK_BALANCE" -> probeStockBalance(rule, expr);
                default -> List.of();
            };
        } catch (Exception e) {
            // 探针自身失败也记为问题（规则配置错误是治理问题的一种）
            return List.of(issue(rule, expr(rule, "ds"), expr(rule, "table"), 1,
                    List.of(Map.of("probeError", String.valueOf(e.getMessage())))));
        }
    }

    private List<GovIssue> probePkUnique(GovRule rule, JsonNode e) {
        String ds = e.get("ds").asText();
        String table = e.get("table").asText();
        String col = e.get("column").asText();
        JdbcTemplate jdbc = datasourceService.jdbc(ds);
        List<Map<String, Object>> dups = jdbc.queryForList(
                "SELECT " + col + " AS value, COUNT(*) AS cnt FROM " + table +
                        " GROUP BY " + col + " HAVING COUNT(*) > 1 LIMIT 5");
        if (dups.isEmpty()) {
            return List.of();
        }
        return List.of(issue(rule, ds, table, dups.size(), dups));
    }

    private List<GovIssue> probeNotNull(GovRule rule, JsonNode e) {
        String ds = e.get("ds").asText();
        String table = e.get("table").asText();
        List<String> cols = new ArrayList<>();
        e.get("columns").forEach(c -> cols.add(c.asText()));
        String condition = cols.stream().map(c -> c + " IS NULL").collect(Collectors.joining(" OR "));
        JdbcTemplate jdbc = datasourceService.jdbc(ds);
        Long cnt = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + condition, Long.class);
        if (cnt == null || cnt == 0) {
            return List.of();
        }
        List<Map<String, Object>> sample = jdbc.queryForList(
                "SELECT * FROM " + table + " WHERE " + condition + " LIMIT 5");
        return List.of(issue(rule, ds, table, cnt.intValue(), sample));
    }

    private List<GovIssue> probeDictConsistent(GovRule rule, JsonNode e) {
        String ds = e.get("ds").asText();
        String table = e.get("table").asText();
        String col = e.get("column").asText();
        // 字典在另一个库：先取字典值，再比对（跨库治理——平台的价值点）
        JdbcTemplate dictJdbc = datasourceService.jdbc(e.get("dictDs").asText());
        String dictTable = e.get("dictTable").asText();
        String dictCol = e.get("dictColumn").asText();
        StringBuilder dictWhere = new StringBuilder("WHERE 1=1 ");
        List<Object> params = new ArrayList<>();
        if (e.has("dictFilter")) {
            e.get("dictFilter").fields().forEachRemaining(f -> {
                dictWhere.append("AND ").append(f.getKey()).append(" = ? ");
                params.add(f.getValue().asText());
            });
        }
        List<String> dictValues = dictJdbc.queryForList(
                "SELECT DISTINCT " + dictCol + " FROM " + dictTable + " " + dictWhere,
                params.toArray()).stream().map(r -> String.valueOf(r.get(dictCol))).toList();
        Set<String> dict = new HashSet<>(dictValues);

        JdbcTemplate jdbc = datasourceService.jdbc(ds);
        List<Map<String, Object>> distribution = jdbc.queryForList(
                "SELECT " + col + " AS value, COUNT(*) AS cnt FROM " + table + " GROUP BY " + col);
        List<Map<String, Object>> outliers = distribution.stream()
                .filter(r -> !dict.contains(String.valueOf(r.get("value"))))
                .map(r -> Map.of("value", String.valueOf(r.get("value")), "cnt", r.get("cnt"),
                        "note", "字典(" + dictTable + "." + dictCol + ")中无此值"))
                .collect(Collectors.toList());
        if (outliers.isEmpty()) {
            return List.of();
        }
        int hit = outliers.stream().mapToInt(o -> ((Number) o.get("cnt")).intValue()).sum();
        return List.of(issue(rule, ds, table, hit, outliers.stream().limit(5).collect(Collectors.toList())));
    }

    private List<GovIssue> probeRefIntact(GovRule rule, JsonNode e) {
        String ds = e.get("ds").asText();
        String table = e.get("table").asText();
        String col = e.get("column").asText();
        // 参照键集合（可跨库）
        JdbcTemplate refJdbc = datasourceService.jdbc(e.get("refDs").asText());
        Set<String> refKeys = new HashSet<>(refJdbc.queryForList(
                "SELECT " + e.get("refColumn").asText() + " FROM " + e.get("refTable").asText())
                .stream().map(r -> String.valueOf(r.get(e.get("refColumn").asText()))).toList());

        JdbcTemplate jdbc = datasourceService.jdbc(ds);
        List<String> keys = jdbc.queryForList(
                "SELECT " + col + " AS k FROM " + table + " WHERE " + col + " IS NOT NULL")
                .stream().map(r -> String.valueOf(r.get("k"))).toList();
        List<String> orphans = keys.stream().filter(k -> !refKeys.contains(k)).toList();
        if (orphans.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> sample = orphans.stream().limit(5)
                .map(k -> Map.<String, Object>of("orphanKey", k, "note", "在 " + e.get("refTable").asText() + " 中无对应记录"))
                .collect(Collectors.toList());
        return List.of(issue(rule, ds, table, orphans.size(), sample));
    }

    private List<GovIssue> probeStockBalance(GovRule rule, JsonNode e) {
        String ds = e.get("ds").asText();
        String keyCol = e.get("keyColumn").asText();
        String qtyCol = e.get("qtyColumn").asText();
        JdbcTemplate jdbc = datasourceService.jdbc(ds);
        Map<String, Integer> stock = toQtyMap(jdbc.queryForList(
                "SELECT " + keyCol + " AS k, " + qtyCol + " AS q FROM " + e.get("stockTable").asText()), qtyCol);
        Map<String, Integer> inSum = toQtyMap(jdbc.queryForList(
                "SELECT " + keyCol + " AS k, SUM(" + qtyCol + ") AS q FROM " + e.get("inTable").asText() + " GROUP BY " + keyCol), "q");
        Map<String, Integer> outSum = toQtyMap(jdbc.queryForList(
                "SELECT " + keyCol + " AS k, SUM(" + qtyCol + ") AS q FROM " + e.get("outTable").asText() + " GROUP BY " + keyCol), "q");

        List<Map<String, Object>> mismatches = new ArrayList<>();
        for (Map.Entry<String, Integer> s : stock.entrySet()) {
            int expect = inSum.getOrDefault(s.getKey(), 0) - outSum.getOrDefault(s.getKey(), 0);
            if (expect != s.getValue()) {
                mismatches.add(Map.of("drug", s.getKey(), "账面库存", s.getValue(), "应为(Σ入-Σ出)", expect));
            }
        }
        if (mismatches.isEmpty()) {
            return List.of();
        }
        return List.of(issue(rule, ds, e.get("stockTable").asText(), mismatches.size(),
                mismatches.stream().limit(5).collect(Collectors.toList())));
    }

    private Map<String, Integer> toQtyMap(List<Map<String, Object>> rows, String qtyCol) {
        Map<String, Integer> map = new HashMap<>();
        for (Map<String, Object> r : rows) {
            Object q = r.containsKey("q") ? r.get("q") : r.get(qtyCol);
            map.put(String.valueOf(r.get("k")), q == null ? 0 : ((Number) q).intValue());
        }
        return map;
    }

    private String expr(GovRule rule, String key) {
        try {
            JsonNode e = objectMapper.readTree(rule.getExprJson());
            return e.has(key) ? e.get(key).asText() : "-";
        } catch (Exception ex) {
            return "-";
        }
    }

    private GovIssue issue(GovRule rule, String ds, String table, int hitCount, List<Map<String, Object>> sample) {
        GovIssue issue = new GovIssue();
        issue.setRuleCode(rule.getRuleCode());
        issue.setRuleName(rule.getRuleName());
        issue.setRuleType(rule.getRuleType());
        issue.setSeverity(rule.getSeverity());
        issue.setConceptCode(rule.getConceptCode());
        issue.setDsCode(ds);
        issue.setTableName(table);
        issue.setHitCount(hitCount);
        try {
            issue.setSampleJson(objectMapper.writeValueAsString(sample));
        } catch (Exception e) {
            issue.setSampleJson("[]");
        }
        return issue;
    }

    // ---------- 查询 ----------

    /** 治理总览：物理表数 / 映射覆盖率 / 质量分 / 未处理问题 */
    public Map<String, Object> overview() {
        long tableCount = physicalTableMapper.selectCount(null);
        long colCount = physicalColumnMapper.selectCount(null);
        long mappedCount = mappingMapper.selectList(new LambdaQueryWrapper<Mapping>()
                .eq(Mapping::getStatus, "ACTIVE")).stream() // 覆盖率只统计生效映射（V30）
                .map(m -> m.getDsCode() + "@" + m.getTableName() + "@" + m.getColumnName())
                .distinct().count();

        GovScan latest = latestScan();
        long openIssues = 0;
        if (latest != null) {
            openIssues = govIssueMapper.selectCount(new LambdaQueryWrapper<GovIssue>()
                    .eq(GovIssue::getScanId, latest.getId()).eq(GovIssue::getStatus, "未处理"));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tableCount", tableCount);
        result.put("columnCount", colCount);
        result.put("mappedCount", mappedCount);
        result.put("coverage", colCount == 0 ? 0 : Math.round(1000f * mappedCount / colCount) / 10f);
        result.put("qualityScore", latest == null ? null : latest.getQualityScore());
        result.put("openIssues", openIssues);
        result.put("lastScan", latest);
        return result;
    }

    /** 每张物理表的治理画像：数据量 / 映射覆盖 / 问题数 / 责任域 */
    public List<Map<String, Object>> tables() {
        List<PhysicalTable> tables = physicalTableMapper.selectList(null);
        List<PhysicalColumn> columns = physicalColumnMapper.selectList(null);
        List<Mapping> mappings = mappingMapper.selectList(new LambdaQueryWrapper<Mapping>()
                .eq(Mapping::getStatus, "ACTIVE")); // 治理画像只看生效映射（V30）
        Map<String, Concept> concepts = conceptMapper.selectList(null).stream()
                .collect(Collectors.toMap(Concept::getCode, c -> c, (a, b) -> a));

        Map<String, Long> colCountByTable = columns.stream().collect(Collectors.groupingBy(
                c -> c.getDsCode() + "@" + c.getTableName(), Collectors.counting()));
        // 覆盖率按去重后的列数计：同一列映射到多个概念属性（如名称列兼作显示值）只算一次
        Map<String, Long> mappedByTable = mappings.stream().collect(Collectors.groupingBy(
                m -> m.getDsCode() + "@" + m.getTableName(),
                Collectors.collectingAndThen(
                        Collectors.mapping(Mapping::getColumnName, Collectors.toSet()),
                        s -> (long) s.size())));
        Map<String, String> domainByTable = new HashMap<>();
        for (Mapping m : mappings) {
            Concept c = concepts.get(m.getConceptCode());
            if (c != null) {
                domainByTable.putIfAbsent(m.getDsCode() + "@" + m.getTableName(), c.getDomainCode());
            }
        }

        GovScan latest = latestScan();
        Map<String, Long> issuesByTable = new HashMap<>();
        Map<String, String> worstByTable = new HashMap<>();
        if (latest != null) {
            List<GovIssue> issues = govIssueMapper.selectList(new LambdaQueryWrapper<GovIssue>()
                    .eq(GovIssue::getScanId, latest.getId()).eq(GovIssue::getStatus, "未处理"));
            for (GovIssue i : issues) {
                String key = i.getDsCode() + "@" + i.getTableName();
                issuesByTable.merge(key, 1L, Long::sum);
                if ("高".equals(i.getSeverity()) || !worstByTable.containsKey(key)) {
                    worstByTable.put(key, i.getSeverity());
                }
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (PhysicalTable t : tables) {
            String key = t.getDsCode() + "@" + t.getTableName();
            long total = colCountByTable.getOrDefault(key, 0L);
            long mapped = mappedByTable.getOrDefault(key, 0L);
            JdbcTemplate jdbc = datasourceService.jdbc(t.getDsCode());
            Long rows = jdbc.queryForObject("SELECT COUNT(*) FROM " + t.getTableName(), Long.class);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("dsCode", t.getDsCode());
            row.put("tableName", t.getTableName());
            row.put("tableComment", t.getTableComment());
            row.put("rowCount", rows == null ? 0 : rows);
            row.put("colCount", total);
            row.put("mappedCount", mapped);
            row.put("coverage", total == 0 ? 0 : Math.round(1000f * mapped / total) / 10f);
            row.put("domain", domainByTable.getOrDefault(key, "未认领"));
            row.put("issueCount", issuesByTable.getOrDefault(key, 0L));
            String worst = worstByTable.get(key);
            row.put("health", worst == null ? "优" : ("高".equals(worst) ? "差" : "良"));
            result.add(row);
        }
        result.sort((a, b) -> Long.compare((Long) b.get("issueCount"), (Long) a.get("issueCount")));
        return result;
    }

    /** 最近一次扫描的问题清单（分页） */
    public PageResult<GovIssue> issues(int pageNum, int pageSize) {
        GovScan latest = latestScan();
        if (latest == null) {
            return PageResult.of(List.of(), 0, pageNum, pageSize);
        }
        long total = govIssueMapper.selectCount(new LambdaQueryWrapper<GovIssue>()
                .eq(GovIssue::getScanId, latest.getId()));
        List<GovIssue> list = govIssueMapper.selectList(new LambdaQueryWrapper<GovIssue>()
                .eq(GovIssue::getScanId, latest.getId())
                .orderByAsc(GovIssue::getId)
                .last("LIMIT " + pageSize + " OFFSET " + (pageNum - 1) * pageSize));
        return PageResult.of(list, total, pageNum, pageSize);
    }

    /** 处理问题（标记已处理） */
    public void resolveIssue(Long id) {
        GovIssue issue = new GovIssue();
        issue.setId(id);
        issue.setStatus("已处理");
        govIssueMapper.updateById(issue);
    }

    /**
     * 治理问题一键转 AI 客服工单（治理 → 客诉 → 诊断 → 处置闭环）。
     * 幂等：同一规则已有工单则直接复用。GOV-004（检验状态字典裂缝）与客诉根因案例同因，
     * 转工单时落到 FEE_DETAIL 概念并携带受影响患者，AI 诊断可直接复用探针引擎。
     */
    public Map<String, Object> issueToTicket(Long issueId) {
        GovIssue issue = govIssueMapper.selectById(issueId);
        if (issue == null) {
            throw new BizException("治理问题不存在: " + issueId);
        }
        String refNo = "T-GOV-" + issue.getRuleCode();
        LinkNode existing = linkService.getByRefNo(refNo);
        if (existing != null) {
            return Map.of("ticketId", existing.getId(), "refNo", refNo, "reused", true);
        }

        LinkNode ticket = new LinkNode();
        ticket.setNodeType("TICKET");
        ticket.setRefNo(refNo);
        ticket.setTitle("数据治理发现：" + issue.getRuleName()
                + "（" + issue.getDsCode() + "." + issue.getTableName() + " 命中 " + issue.getHitCount() + " 行）");
        ticket.setStatus("待处理");
        ticket.setOccurredAt(LocalDateTime.now());

        if ("GOV-004".equals(issue.getRuleCode())) {
            // 与客诉根因同因（LIS 撤销码 C 未映射 → 取消未退费）：落到费用概念，带一名受影响患者
            ticket.setConceptCode("FEE_DETAIL");
            JdbcTemplate his = datasourceService.jdbc("DS_HIS");
            List<Map<String, Object>> affected = his.queryForList(
                    "SELECT f.inhos_no, i.patient_name FROM fee_detail f " +
                            "JOIN medical_order o ON f.order_id = o.order_id " +
                            "JOIN inpatient i ON i.inhos_no = f.inhos_no " +
                            "WHERE o.order_status = '2' AND f.fee_status = '1' LIMIT 1");
            if (!affected.isEmpty()) {
                ticket.setPayload("{\"inhos_no\":\"" + affected.get(0).get("inhos_no")
                        + "\",\"patient\":\"" + Masking.maskName(String.valueOf(affected.get(0).get("patient_name"))) + "\"}");
            }
        } else {
            ticket.setConceptCode(issue.getConceptCode());
        }
        linkService.save(ticket);
        return Map.of("ticketId", ticket.getId(), "refNo", refNo, "reused", false);
    }

    private GovScan latestScan() {
        List<GovScan> scans = govScanMapper.selectList(new LambdaQueryWrapper<GovScan>()
                .orderByDesc(GovScan::getId).last("LIMIT 1"));
        return scans.isEmpty() ? null : scans.get(0);
    }
}
