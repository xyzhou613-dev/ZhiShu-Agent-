package com.bemodel.clinical;

import com.bemodel.common.BizException;
import com.bemodel.common.Masking;
import com.bemodel.common.PageResult;
import com.bemodel.datasource.service.DatasourceService;
import com.bemodel.llm.DeepSeekClient;
import com.bemodel.modeling.service.ReleaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 病案内涵质控：编排器。规则判定全部委托 QcRuleEngine（读取 bm_rule 中
 * engine='QC' 的已发布规则表达式执行，新规则平台配置即生效、免开发）；
 * 本服务负责装配病案上下文、组织溯源链、LLM 质控意见与结果持久化。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QcService {

    private final DatasourceService datasourceService;
    private final DeepSeekClient deepSeekClient;
    private final ReleaseService releaseService;
    private final QcRuleEngine ruleEngine;
    private final com.bemodel.clinical.mapper.QcResultMapper qcResultMapper;
    private final ObjectMapper objectMapper;

    public List<Map<String, Object>> records(String keyword) {
        JdbcTemplate emr = datasourceService.jdbc("DS_EMR");
        if (keyword != null && !keyword.isBlank()) {
            return Masking.maskPatientRows(emr.queryForList(
                    "SELECT * FROM emr_record WHERE patient_name LIKE ? OR inhos_no LIKE ? ORDER BY create_time DESC",
                    "%" + keyword + "%", "%" + keyword + "%"));
        }
        return Masking.maskPatientRows(emr.queryForList("SELECT * FROM emr_record ORDER BY create_time DESC"));
    }

    /** 病案列表服务端分页（病案会持续累积） */
    public PageResult<Map<String, Object>> recordsPage(String keyword, int pageNum, int pageSize) {
        JdbcTemplate emr = datasourceService.jdbc("DS_EMR");
        String where = "WHERE 1=1 ";
        List<Object> params = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where += "AND (patient_name LIKE ? OR inhos_no LIKE ?) ";
            params.add("%" + keyword + "%");
            params.add("%" + keyword + "%");
        }
        Long total = emr.queryForObject("SELECT COUNT(*) FROM emr_record " + where, Long.class, params.toArray());
        List<Map<String, Object>> list = emr.queryForList(
                "SELECT * FROM emr_record " + where + "ORDER BY create_time DESC LIMIT " + pageSize
                        + " OFFSET " + (pageNum - 1) * pageSize, params.toArray());
        return PageResult.of(Masking.maskPatientRows(list), total == null ? 0 : total, pageNum, pageSize);
    }

    /** 对单份病案执行内涵质控：规则引擎全量校验，发现项持久化并回写病案状态 */
    public Map<String, Object> check(String recordId) {
        JdbcTemplate emr = datasourceService.jdbc("DS_EMR");
        JdbcTemplate his = datasourceService.jdbc("DS_HIS");

        List<Map<String, Object>> rows = emr.queryForList(
                "SELECT * FROM emr_record WHERE record_id = ?", recordId);
        if (rows.isEmpty()) {
            throw new BizException("病案不存在: " + recordId);
        }
        Map<String, Object> record = rows.get(0);
        String inhosNo = String.valueOf(record.get("inhos_no"));
        List<String> diags = Arrays.stream(String.valueOf(record.get("diag_list")).split("[，,]"))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        Map<String, Object> patient = his.queryForMap(
                "SELECT sex, patient_name FROM inpatient WHERE inhos_no = ?", inhosNo);

        Map<String, Object> engineOut = ruleEngine.runRules(
                inhosNo, String.valueOf(patient.get("sex")), diags,
                his, datasourceService.jdbc("DS_LIS"),
                datasourceService.jdbc("DS_PACS"), emr,
                datasourceService.jdbc("DS_PHARMACY"));
        @SuppressWarnings("unchecked")
        List<QcRuleEngine.Finding> engineFindings = (List<QcRuleEngine.Finding>) engineOut.get("findings");
        List<Map<String, Object>> findings = engineFindings.stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ruleCode", f.ruleCode);
            m.put("ruleName", f.ruleName);
            m.put("severity", f.severity);
            m.put("passed", false);
            m.put("evidence", f.evidence);
            if (f.axiom != null) {
                m.put("axiom", f.axiom);
            }
            if (f.sources != null && !f.sources.isEmpty()) {
                m.put("sources", f.sources);
            }
            return m;
        }).toList();

        boolean pass = findings.isEmpty();
        String version = releaseService.currentTag();
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("ontologyVersion", version == null ? "未发布" : version);
        trace.put("rulesCited", engineOut.get("rulesCited"));
        trace.put("axiomsCited", engineOut.get("axiomsCited"));
        trace.put("conceptsInvolved", List.of("EMR_RECORD", "DIAGNOSIS", "PROCEDURE", "LAB_REPORT", "CHECK_REPORT", "MEDICAL_ORDER"));

        // LLM 组织质控意见（结构化发现 → 人话；失败模板降级）
        String summary;
        boolean llmUsed = false;
        StringBuilder ctx = new StringBuilder(String.format("病案 %s（患者 %s，%s，主要诊断：%s，全部诊断：%s）内涵质控发现 %d 项问题：\n",
                recordId, Masking.maskName(String.valueOf(record.get("patient_name"))), record.get("record_type"),
                record.get("diag_main"), record.get("diag_list"), findings.size()));
        for (Map<String, Object> f : findings) {
            ctx.append("- [").append(f.get("ruleCode")).append("] ").append(f.get("evidence")).append('\n');
        }
        if (findings.isEmpty()) {
            ctx.append("未发现逻辑矛盾。\n");
        }
        Optional<String> llm = deepSeekClient.chat("QC_REVIEW",
                "你是医院病案质控专家。基于质控发现输出质控意见：一、总体结论（通过/不通过）；二、问题清单的临床风险解读；三、整改建议（给书写医生）。专业简洁，300字以内。",
                ctx.toString());
        if (llm.isPresent()) {
            summary = llm.get();
            llmUsed = true;
        } else {
            summary = pass ? "未发现内涵逻辑矛盾，质控通过。"
                    : String.format("质控不通过，发现 %d 项逻辑矛盾：%s。请书写医生核实整改。",
                            findings.size(),
                            String.join("；", findings.stream().map(f -> String.valueOf(f.get("evidence"))).toList()));
        }

        persist(recordId, inhosNo, String.valueOf(record.get("record_type")), pass, findings, trace, llmUsed, summary);
        emr.update("UPDATE emr_record SET qc_status = ? WHERE record_id = ?", pass ? "通过" : "不通过", recordId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recordId", recordId);
        result.put("pass", pass);
        result.put("findings", findings);
        result.put("trace", trace);
        result.put("llmSummary", summary);
        result.put("llmUsed", llmUsed);
        return result;
    }

    /** 最近一次质控结果（打开病案详情时回显，无需重跑） */
    public QcResult latestResult(String recordId) {
        return qcResultMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<QcResult>()
                .eq(QcResult::getRecordId, recordId).orderByDesc(QcResult::getId).last("LIMIT 1"));
    }

    public Map<String, Object> checkAll() {
        List<Map<String, Object>> all = records(null);
        int pass = 0;
        int fail = 0;
        List<Map<String, Object>> details = new ArrayList<>();
        for (Map<String, Object> r : all) {
            Map<String, Object> one = check(String.valueOf(r.get("record_id")));
            if (Boolean.TRUE.equals(one.get("pass"))) {
                pass++;
            } else {
                fail++;
            }
            details.add(Map.of("recordId", r.get("record_id"), "patient",
                    Masking.maskName(String.valueOf(r.get("patient_name"))),
                    "pass", one.get("pass"), "findingCount", ((List<?>) one.get("findings")).size()));
        }
        return Map.of("total", all.size(), "pass", pass, "fail", fail, "details", details);
    }

    private void persist(String recordId, String inhosNo, String recordType, boolean pass,
                         List<Map<String, Object>> findings, Map<String, Object> trace,
                         boolean llmUsed, String summary) {
        try {
            QcResult result = new QcResult();
            result.setRecordId(recordId);
            result.setInhosNo(inhosNo);
            result.setRecordType(recordType);
            result.setPassFlag(pass ? 1 : 0);
            result.setFindingsJson(objectMapper.writeValueAsString(findings));
            result.setTraceJson(objectMapper.writeValueAsString(trace));
            result.setLlmUsed(llmUsed ? 1 : 0);
            result.setLlmSummary(summary);
            result.setCreatedAt(LocalDateTime.now());
            qcResultMapper.insert(result);
        } catch (Exception e) {
            throw new RuntimeException("质控结果落库失败: " + e.getMessage(), e);
        }
    }
}
