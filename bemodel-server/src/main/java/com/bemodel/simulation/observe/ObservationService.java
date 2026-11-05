package com.bemodel.simulation.observe;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bemodel.governance.GovRule;
import com.bemodel.governance.mapper.GovRuleMapper;
import com.bemodel.ontology.entity.Metric;
import com.bemodel.ontology.mapper.MetricMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 观察面(spec §3):巡检探针(bm_metric probe_sql 于 DS_LAB 只读执行)+账实规则(GOV-007 同款
 * Σ入-Σ出,按本次波及药品核对)。不回写 bm_metric.last_val——主库零写入红线。
 */
@Component
public class ObservationService {

    /** 拼进 SQL 的表列名虽来自规则配置(受控种子),仍按标识符白名单校验——与引擎/施加器同一纪律 */
    private static final java.util.regex.Pattern SAFE_IDENT = java.util.regex.Pattern.compile("[a-zA-Z0-9_]+");

    private final MetricMapper metricMapper;
    private final GovRuleMapper govRuleMapper;
    private final ObjectMapper objectMapper;

    public ObservationService(MetricMapper metricMapper, GovRuleMapper govRuleMapper,
                              ObjectMapper objectMapper) {
        this.metricMapper = metricMapper;
        this.govRuleMapper = govRuleMapper;
        this.objectMapper = objectMapper;
    }

    /** 一次观察快照;探针/规则失败如实记 error,不伪装结论 */
    public Map<String, Object> snapshot(JdbcTemplate lab, String drugCode) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> metrics = new ArrayList<>();
        List<Metric> probes = metricMapper.selectList(new LambdaQueryWrapper<Metric>()
                .eq(Metric::getConceptCode, "DRUG_STOCK").isNotNull(Metric::getProbeSql)
                .orderByAsc(Metric::getMetricCode));
        for (Metric m : probes) {
            Map<String, Object> mi = new LinkedHashMap<>();
            mi.put("code", m.getMetricCode());
            mi.put("name", m.getName());
            mi.put("warnThreshold", m.getWarnThreshold());
            try {
                Number v = lab.queryForObject(m.getProbeSql(), Number.class);
                mi.put("value", v == null ? null : v.doubleValue());
                mi.put("alert", m.getWarnThreshold() != null && v != null
                        && v.doubleValue() > m.getWarnThreshold());
            } catch (Exception e) {
                mi.put("error", String.valueOf(e.getMessage()));
            }
            metrics.add(mi);
        }
        out.put("metrics", metrics);
        out.put("rule", stockBalance(lab, drugCode));
        return out;
    }

    /** 账实规则:读 GOV expr(stockTable/inTable/outTable/keyColumn/qtyColumn),只核对本次波及药品 */
    private Map<String, Object> stockBalance(JdbcTemplate lab, String drugCode) {
        Map<String, Object> out = new LinkedHashMap<>();
        GovRule rule = govRuleMapper.selectOne(new LambdaQueryWrapper<GovRule>()
                .eq(GovRule::getConceptCode, "DRUG_STOCK").eq(GovRule::getRuleType, "STOCK_BALANCE")
                .orderByAsc(GovRule::getId).last("LIMIT 1"));
        if (rule == null) {
            out.put("pass", null);
            out.put("detail", "没有找到账实规则(不造规则)");
            return out;
        }
        out.put("ruleCode", rule.getRuleCode());
        out.put("ruleName", rule.getRuleName());
        try {
            JsonNode e = objectMapper.readTree(rule.getExprJson());
            for (String k : new String[]{"stockTable", "inTable", "outTable", "keyColumn", "qtyColumn"}) {
                if (!SAFE_IDENT.matcher(e.get(k).asText()).matches()) {
                    throw new IllegalArgumentException("规则配置含非法标识符: " + k);
                }
            }
            String keyCol = e.get("keyColumn").asText();
            String qtyCol = e.get("qtyColumn").asText();
            Integer stock = lab.queryForObject("SELECT " + qtyCol + " FROM " + e.get("stockTable").asText()
                    + " WHERE " + keyCol + " = ?", Integer.class, drugCode);
            Integer inSum = lab.queryForObject("SELECT IFNULL(SUM(" + qtyCol + "),0) FROM "
                    + e.get("inTable").asText() + " WHERE " + keyCol + " = ?", Integer.class, drugCode);
            Integer outSum = lab.queryForObject("SELECT IFNULL(SUM(" + qtyCol + "),0) FROM "
                    + e.get("outTable").asText() + " WHERE " + keyCol + " = ?", Integer.class, drugCode);
            int expect = (inSum == null ? 0 : inSum) - (outSum == null ? 0 : outSum);
            boolean pass = stock != null && stock == expect;
            out.put("pass", pass);
            out.put("detail", "账面 " + stock + ",按 Σ入-Σ出 应为 " + expect
                    + (pass ? ",账实相符" : ",账实不符"));
        } catch (Exception ex) {
            out.put("pass", null);
            out.put("detail", "规则执行失败: " + ex.getMessage());
        }
        return out;
    }
}
