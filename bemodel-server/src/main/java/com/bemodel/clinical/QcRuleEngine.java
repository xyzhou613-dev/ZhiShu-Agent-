package com.bemodel.clinical;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bemodel.modeling.entity.Axiom;
import com.bemodel.modeling.entity.Rule;
import com.bemodel.modeling.mapper.AxiomMapper;
import com.bemodel.modeling.mapper.RuleMapper;
import com.bemodel.ontology.entity.Disjoint;
import com.bemodel.ontology.entity.Term;
import com.bemodel.ontology.mapper.TermMapper;
import com.bemodel.ontology.service.DisjointService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 质控规则引擎：读取 bm_rule 中 engine='QC' 且已发布的规则，
 * 按其 expr_json 表达式对产品库真实数据执行探针校验。
 * 新增规则 = 在平台配置一条表达式（UI/SQL 均可），无需改代码、无需发版。
 *
 * 关键词判定先经 termExpand 术语归一：硬编码关键词若在 bm_term 挂到概念，
 * 扩展为该概念名下全部方言/标准/外文术语再比对（本体驱动，查询失败降级为原词不断链）。
 *
 * 已支持的规则类型（type）：
 *  DIAG_REQUIRES_ITEM              诊断须有客观依据（检验/药品）
 *  PREOP_REQUIRES                  手术前必查项目齐全且早于手术时间
 *  ABNORMAL_REQUIRES_COVER         检验危急值须被诊断或处置覆盖
 *  COMPLICATION_REQUIRES_ITEM      并发症须有处置
 *  SEX_DISJOINT_DIAG               性别-诊断互斥（关键词经术语扩展）
 *  EXAM_ABNORMAL_REQUIRES_COVER    检查报告异常须处置（PACS）
 *  ALLERGY_DISJOINT                过敏禁忌（过敏原经术语扩展）
 *  DOSE_LIMIT                      单日剂量上限
 */
@Service
@RequiredArgsConstructor
public class QcRuleEngine {

    private final RuleMapper ruleMapper;
    private final AxiomMapper axiomMapper;
    private final DisjointService disjointService;
    private final TermMapper termMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public static class Finding {
        public String ruleCode;
        public String ruleName;
        public String severity;
        public String evidence;
        public String axiom;
        /** 这条发现跨了哪些系统的数据（给业务人员看懂"数据从哪来"） */
        public List<String> sources;
    }

    /** 规则类型 → 数据来源（跨库关联说明，与解释器的真实取数一一对应） */
    private static final Map<String, List<String>> RULE_SOURCES = Map.of(
            "DIAG_REQUIRES_ITEM", List.of("诊断 ← 病案（EMR）", "医嘱执行 ← HIS"),
            "PREOP_REQUIRES", List.of("手术记录 ← 病案（EMR）", "术前检验 ← HIS / LIS"),
            "ABNORMAL_REQUIRES_COVER", List.of("检验报告 ← LIS", "诊断与处置 ← 病案（EMR）/ HIS"),
            "COMPLICATION_REQUIRES_ITEM", List.of("并发症诊断 ← 病案（EMR）", "处置医嘱 ← HIS"),
            "SEX_DISJOINT_DIAG", List.of("患者性别 ← HIS 住院登记", "诊断 ← 病案（EMR）"),
            "EXAM_ABNORMAL_REQUIRES_COVER", List.of("检查报告 ← PACS", "诊断与处置 ← 病案（EMR）/ HIS"),
            "ALLERGY_DISJOINT", List.of("过敏史 ← 病案（EMR）", "药品医嘱 ← HIS", "药品过敏原 ← 药房·药品字典"),
            "DOSE_LIMIT", List.of("医嘱剂量/频次 ← HIS", "日最大剂量 ← 药房·药品字典"));

    /** 对一份病案执行全部已发布 QC 规则，返回发现与引用的规则/公理 */
    public Map<String, Object> runRules(String inhosNo, String sex, List<String> diags,
                                        JdbcTemplate his, JdbcTemplate lis,
                                        JdbcTemplate pacs, JdbcTemplate emr, JdbcTemplate pharmacy) {
        List<Finding> findings = new ArrayList<>();
        Set<String> rulesCited = new LinkedHashSet<>();
        Set<String> axiomsCited = new LinkedHashSet<>();

        List<Rule> rules = ruleMapper.selectList(new LambdaQueryWrapper<Rule>()
                .eq(Rule::getEngine, "QC").eq(Rule::getStatus, "PUBLISHED"));
        for (Rule rule : rules) {
            try {
                JsonNode expr = objectMapper.readTree(rule.getExprJson());
                String type = expr.get("type").asText();
                List<Finding> hits = switch (type) {
                    case "DIAG_REQUIRES_ITEM" -> diagRequiresItem(rule, expr, diags, inhosNo, his);
                    case "PREOP_REQUIRES" -> preopRequires(rule, expr, inhosNo, emr, his);
                    case "ABNORMAL_REQUIRES_COVER" -> abnormalRequiresCover(rule, expr, diags, inhosNo, lis, his);
                    case "COMPLICATION_REQUIRES_ITEM" -> complicationRequiresItem(rule, expr, diags, inhosNo, his);
                    case "SEX_DISJOINT_DIAG" -> sexDisjoint(rule, expr, sex, diags);
                    case "EXAM_ABNORMAL_REQUIRES_COVER" -> examAbnormal(rule, expr, diags, inhosNo, pacs, emr, his);
                    case "ALLERGY_DISJOINT" -> allergyDisjoint(rule, expr, inhosNo, his, emr, pharmacy);
                    case "DOSE_LIMIT" -> doseLimit(rule, expr, inhosNo, his, pharmacy);
                    default -> List.of();
                };
                hits.forEach(f -> f.sources = RULE_SOURCES.getOrDefault(type, List.of()));
                if (!hits.isEmpty()) {
                    rulesCited.add(rule.getRuleCode());
                    if (expr.hasNonNull("axiom")) {
                        axiomsCited.add(expr.get("axiom").asText());
                    }
                    findings.addAll(hits);
                }
            } catch (Exception e) {
                // 单条规则表达式异常不阻断整体质控
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("findings", findings);
        out.put("rulesCited", rulesCited);
        out.put("axiomsCited", axiomsCited);
        return out;
    }

    // ---------- 规则类型解释器 ----------

    /** 诊断关键词命中 → 要求存在已执行医嘱（检验/药品） */
    private List<Finding> diagRequiresItem(Rule rule, JsonNode expr, List<String> diags,
                                           String inhosNo, JdbcTemplate his) {
        List<Finding> out = new ArrayList<>();
        for (JsonNode c : expr.get("cases")) {
            List<String> keywords = jsonList(c.get("diagKeywords"));
            for (String diag : diags) {
                boolean hit = keywords.stream().anyMatch(diag::contains);
                if (hit && !hasExecutedItem(his, inhosNo, c.get("kind").asText(), jsonList(c.get("codes")))) {
                    out.add(mk(rule, expr, String.format("诊断「%s」缺少客观依据：未见%s记录",
                            diag, c.get("requireName").asText())));
                }
            }
        }
        return out;
    }

    /** 手术存在 → 术前必查项目须已执行且早于手术时间 */
    private List<Finding> preopRequires(Rule rule, JsonNode expr, String inhosNo,
                                        JdbcTemplate emr, JdbcTemplate his) {
        List<Finding> out = new ArrayList<>();
        List<Map<String, Object>> surgeries = emr.queryForList(
                "SELECT * FROM emr_surgery WHERE inhos_no = ?", inhosNo);
        for (Map<String, Object> surgery : surgeries) {
            String procTime = String.valueOf(surgery.get("proc_time"));
            List<String> missing = new ArrayList<>();
            for (JsonNode req : expr.get("requires")) {
                if (!hasExecutedItemBefore(his, inhosNo, req.get("kind").asText(),
                        jsonList(req.get("codes")), procTime)) {
                    missing.add(req.get("requireName").asText());
                }
            }
            if (!missing.isEmpty()) {
                out.add(mk(rule, expr, String.format("手术「%s」(%s) 术前缺少：%s",
                        surgery.get("proc_name"), procTime, String.join("、", missing))));
            }
        }
        return out;
    }

    /** 检验危急值 → 须被感染类诊断或抗生素处置覆盖 */
    private List<Finding> abnormalRequiresCover(Rule rule, JsonNode expr, List<String> diags,
                                                String inhosNo, JdbcTemplate lis, JdbcTemplate his) {
        List<Finding> out = new ArrayList<>();
        List<Map<String, Object>> abnormals = lis.queryForList(
                "SELECT report_id, item_code, report_time FROM lab_report WHERE patient_no = ? AND result_status = 'A'",
                inhosNo);
        List<String> keywords = jsonList(expr.get("coverDiagKeywords"));
        List<String> coverCodes = jsonList(expr.get("coverCodes"));
        for (Map<String, Object> ab : abnormals) {
            boolean byDiag = diags.stream().anyMatch(d -> keywords.stream().anyMatch(d::contains));
            boolean byDrug = hasExecutedItem(his, inhosNo, expr.get("coverKind").asText(), coverCodes);
            if (!byDiag && !byDrug) {
                out.add(mk(rule, expr, String.format("检验报告 %s（%s，%s）结果异常，但病案中无对应诊断或处置医嘱",
                        ab.get("report_id"), ab.get("item_code"), ab.get("report_time"))));
            }
        }
        return out;
    }

    /** 并发症关键词命中 → 须有处置医嘱 */
    private List<Finding> complicationRequiresItem(Rule rule, JsonNode expr, List<String> diags,
                                                   String inhosNo, JdbcTemplate his) {
        List<Finding> out = new ArrayList<>();
        for (JsonNode c : expr.get("cases")) {
            List<String> keywords = jsonList(c.get("diagKeywords"));
            for (String diag : diags) {
                boolean hit = keywords.stream().anyMatch(diag::contains);
                if (hit && !hasExecutedItem(his, inhosNo, c.get("kind").asText(), jsonList(c.get("codes")))) {
                    out.add(mk(rule, expr, String.format("并发症「%s」未见处置：无%s医嘱",
                            diag, c.get("requireName").asText())));
                }
            }
        }
        return out;
    }

    /** 性别-诊断互斥（公理驱动；互斥来源优先 bm_concept_disjoint 结构化公理，查不到回退自由文本公理） */
    private List<Finding> sexDisjoint(Rule rule, JsonNode expr, String sex, List<String> diags) {
        List<Finding> out = new ArrayList<>();
        if (!expr.get("sex").asText().equals(sex)) {
            return out;
        }
        Disjoint basis = disjointBasis(expr);
        // 术语归一：硬编码关键词经 bm_term 扩展为同义词族再判定；expandedFrom 记录扩展来源供证据追溯
        List<String> keywords = jsonList(expr.get("diagKeywords"));
        Map<String, String> expandedFrom = new LinkedHashMap<>();
        List<String> expanded = new ArrayList<>();
        for (String k : keywords) {
            for (String t : termExpand(k)) {
                if (!expanded.contains(t)) {
                    expanded.add(t);
                    if (!t.equals(k)) {
                        expandedFrom.put(t, k);
                    }
                }
            }
        }
        for (String diag : diags) {
            String via = expanded.stream().filter(diag::contains).filter(expandedFrom::containsKey)
                    .findFirst().orElse(null);
            if (expanded.stream().anyMatch(diag::contains)) {
                String note = via == null ? "" : String.format("（术语扩展命中：%s→%s）", expandedFrom.get(via), via);
                out.add(mk(rule, expr, (basis != null
                        ? String.format("%s患者出现互斥诊断「%s」（公理表 bm_concept_disjoint：%s ✕ %s）",
                                sex, diag, basis.getConceptACode(), basis.getConceptBCode())
                        : String.format("%s患者出现互斥诊断「%s」（违反公理%s）",
                                sex, diag, expr.get("axiom").asText())) + note));
            }
        }
        return out;
    }

    /** 检查报告异常 → 须被对应诊断/进一步处置覆盖（coverProcedure=true 时手术记录也算处置） */
    private List<Finding> examAbnormal(Rule rule, JsonNode expr, List<String> diags,
                                       String inhosNo, JdbcTemplate pacs, JdbcTemplate emr, JdbcTemplate his) {
        List<Finding> out = new ArrayList<>();
        List<Map<String, Object>> abnormals = pacs.queryForList(
                "SELECT exam_id, item_name, conclusion, report_time FROM exam_report WHERE patient_no = ? AND abnormal_flag = 'Y'",
                inhosNo);
        List<String> keywords = jsonList(expr.get("coverDiagKeywords"));
        boolean hasProcedure = !emr.queryForList(
                "SELECT surg_id FROM emr_surgery WHERE inhos_no = ?", inhosNo).isEmpty();
        for (Map<String, Object> ab : abnormals) {
            String conclusion = String.valueOf(ab.get("conclusion"));
            boolean byDiag = diags.stream().anyMatch(d -> keywords.stream().anyMatch(d::contains))
                    || keywords.stream().anyMatch(k -> conclusion.contains(k)
                            && diags.stream().anyMatch(d -> d.contains(k)));
            boolean byProcedure = expr.hasNonNull("coverProcedure") && expr.get("coverProcedure").asBoolean()
                    && hasProcedure;
            if (!byDiag && !byProcedure) {
                out.add(mk(rule, expr, String.format("检查报告 %s（%s）结论异常「%s」，但无对应诊断或进一步处置",
                        ab.get("exam_id"), ab.get("item_name"), conclusion)));
            }
        }
        return out;
    }

    // ---------- 工具 ----------

    /**
     * 过敏禁忌（公理AX-007）：过敏史（EMR）× 药品过敏原（药房字典）× 已执行药品医嘱（HIS），
     * 三库数据经本体映射对齐后比对——与 SHACL Shape 1（AllergyConstraint）同一语义的平台实现。
     * 互斥来源优先 bm_concept_disjoint 结构化公理，查不到回退 bm_axiom 自由文本（维持原行为）。
     */
    private List<Finding> allergyDisjoint(Rule rule, JsonNode expr, String inhosNo,
                                          JdbcTemplate his, JdbcTemplate emr, JdbcTemplate pharmacy) {
        List<Finding> out = new ArrayList<>();
        List<String> allergies = emr.queryForList(
                        "SELECT allergen FROM patient_allergy WHERE inhos_no = ?", inhosNo)
                .stream().map(r -> String.valueOf(r.get("allergen"))).toList();
        if (allergies.isEmpty()) {
            return out;
        }
        Disjoint basis = disjointBasis(expr);
        Map<String, String> allergenByDrug = new HashMap<>();
        for (Map<String, Object> d : pharmacy.queryForList(
                "SELECT drug_code, allergen FROM drug_dict WHERE allergen IS NOT NULL")) {
            allergenByDrug.put(String.valueOf(d.get("drug_code")), String.valueOf(d.get("allergen")));
        }
        for (Map<String, Object> o : his.queryForList(
                "SELECT order_id, item_code, item_name FROM medical_order " +
                        "WHERE inhos_no = ? AND order_type = '药品' AND order_status = '1'", inhosNo)) {
            String allergen = allergenByDrug.get(String.valueOf(o.get("item_code")));
            if (allergen == null) {
                continue;
            }
            boolean direct = allergies.contains(allergen);
            // 术语归一：过敏史与药品过敏原字面不同时，经 bm_term 词族扩展再比对（如「头孢菌素」⇐「头孢」）
            String via = direct ? null : allergenExpansionBridge(allergies, allergen);
            if (direct || via != null) {
                String note = via == null ? ""
                        : String.format("（术语扩展命中：过敏史「%s」与药品过敏原「%s」同词族）", via, allergen);
                out.add(mk(rule, expr, (basis != null
                        ? String.format("患者对「%s」过敏，已执行医嘱 %s（%s，含%s过敏原）违反过敏禁忌（公理表 bm_concept_disjoint：%s ✕ %s）",
                                allergen, o.get("order_id"), o.get("item_name"), allergen,
                                basis.getConceptACode(), basis.getConceptBCode())
                        : String.format("患者对「%s」过敏，已执行医嘱 %s（%s，含%s过敏原）违反过敏禁忌",
                                allergen, o.get("order_id"), o.get("item_name"), allergen)) + note));
            }
        }
        return out;
    }

    /**
     * 剂量上限（公理AX-008）：单日剂量（单次剂量×频次）> 药品日最大剂量即命中。
     * 频次换算与 SHACL Shape 2（DoseLimitShape）保持一致：bid=2/tid=3/q8h=3/其余=1。
     */
    private List<Finding> doseLimit(Rule rule, JsonNode expr, String inhosNo,
                                    JdbcTemplate his, JdbcTemplate pharmacy) {
        List<Finding> out = new ArrayList<>();
        Map<String, Map<String, Object>> dict = new HashMap<>();
        for (Map<String, Object> d : pharmacy.queryForList(
                "SELECT drug_code, drug_name, max_daily_dose FROM drug_dict WHERE max_daily_dose IS NOT NULL")) {
            dict.put(String.valueOf(d.get("drug_code")), d);
        }
        for (Map<String, Object> o : his.queryForList(
                "SELECT order_id, item_code, item_name, single_dose, dose_unit, frequency FROM medical_order " +
                        "WHERE inhos_no = ? AND order_type = '药品' AND order_status = '1' AND single_dose IS NOT NULL",
                inhosNo)) {
            Map<String, Object> d = dict.get(String.valueOf(o.get("item_code")));
            if (d == null || d.get("max_daily_dose") == null) {
                continue;
            }
            double dose = ((Number) o.get("single_dose")).doubleValue();
            double max = ((Number) d.get("max_daily_dose")).doubleValue();
            int factor = switch (String.valueOf(o.get("frequency"))) {
                case "bid" -> 2;
                case "tid", "q8h" -> 3;
                default -> 1;
            };
            double daily = dose * factor;
            if (daily > max) {
                out.add(mk(rule, expr, String.format("医嘱 %s（%s）单日剂量 %g%s 超日最大剂量 %g%s（%g×%s）",
                        o.get("order_id"), o.get("item_name"), daily, o.get("dose_unit"),
                        max, o.get("dose_unit"), dose, o.get("frequency"))));
            }
        }
        return out;
    }

    /**
     * 结构化互斥依据：expr.axiom 指向的 bm_axiom 自由文本可解析为概念对，
     * 且 bm_concept_disjoint 存在对应互斥行（A-B/B-A 双向）时返回该行；否则返回 null（走原自由文本兜底）。
     */
    private Disjoint disjointBasis(JsonNode expr) {
        if (!expr.hasNonNull("axiom")) {
            return null;
        }
        Axiom axiom = axiomMapper.selectOne(new LambdaQueryWrapper<Axiom>()
                .eq(Axiom::getAxiomCode, expr.get("axiom").asText()).last("LIMIT 1"));
        if (axiom == null) {
            return null;
        }
        String a = disjointService.resolveConceptCode(axiom.getSubject());
        String b = disjointService.resolveConceptCode(axiom.getObject());
        if (a == null || b == null || a.equals(b)) {
            return null;
        }
        return disjointService.findPair(a, b);
    }

    /**
     * 术语归一：关键词若在 bm_term 挂到标准概念，扩展为该概念名下全部术语（方言/标准/外文），
     * 否则返回原词单例。查询异常时降级为原词集合，绝不断链。
     */
    Set<String> termExpand(String keyword) {
        try {
            Term seed = termMapper.selectOne(new LambdaQueryWrapper<Term>()
                    .eq(Term::getTerm, keyword).last("LIMIT 1"), false);
            if (seed == null) {
                return Set.of(keyword);
            }
            Set<String> expanded = new LinkedHashSet<>();
            expanded.add(keyword);
            for (Term t : termMapper.selectList(new LambdaQueryWrapper<Term>()
                    .eq(Term::getConceptCode, seed.getConceptCode()))) {
                expanded.add(t.getTerm());
            }
            return expanded;
        } catch (Exception e) {
            return Set.of(keyword);
        }
    }

    /** 过敏史 × 药品过敏原的术语桥：双方词族扩展后相交则命中，返回过敏史原词；无扩展命中返回 null */
    private String allergenExpansionBridge(List<String> allergies, String allergen) {
        Set<String> allergenTerms = termExpand(allergen);
        for (String a : allergies) {
            if (!a.equals(allergen) && termExpand(a).stream().anyMatch(allergenTerms::contains)) {
                return a;
            }
        }
        return null;
    }

    private Finding mk(Rule rule, JsonNode expr, String evidence) {
        Finding f = new Finding();
        f.ruleCode = rule.getRuleCode();
        f.ruleName = rule.getName();
        f.severity = rule.getSeverity();
        f.evidence = evidence;
        f.axiom = expr.hasNonNull("axiom") ? expr.get("axiom").asText() : null;
        return f;
    }

    private List<String> jsonList(JsonNode arr) {
        List<String> list = new ArrayList<>();
        arr.forEach(n -> list.add(n.asText()));
        return list;
    }

    private boolean hasExecutedItem(JdbcTemplate his, String inhosNo, String kind, List<String> codes) {
        String in = codes.stream().map(c -> "'" + c + "'").reduce((a, b) -> a + "," + b).orElse("''");
        Integer cnt = his.queryForObject(
                "SELECT COUNT(*) FROM medical_order WHERE inhos_no = ? AND order_status = '1' AND item_code IN (" + in + ")",
                Integer.class, inhosNo);
        return cnt != null && cnt > 0;
    }

    private boolean hasExecutedItemBefore(JdbcTemplate his, String inhosNo, String kind,
                                          List<String> codes, String beforeTime) {
        String in = codes.stream().map(c -> "'" + c + "'").reduce((a, b) -> a + "," + b).orElse("''");
        Integer cnt = his.queryForObject(
                "SELECT COUNT(*) FROM medical_order WHERE inhos_no = ? AND order_status = '1' " +
                        "AND item_code IN (" + in + ") AND create_time < ?",
                Integer.class, inhosNo, beforeTime);
        return cnt != null && cnt > 0;
    }
}
