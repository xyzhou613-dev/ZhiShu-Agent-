package com.bemodel.lab.tools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bemodel.clinical.QcRuleEngine;
import com.bemodel.common.BizException;
import com.bemodel.datasource.service.DatasourceService;
import com.bemodel.lab.LabSandboxService;
import com.bemodel.lab.engine.LabTool;
import com.bemodel.modeling.entity.Action;
import com.bemodel.modeling.mapper.ActionMapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collection;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A 臂(AI+本体)工具箱:每个工具都是本体能力(已发布规则/动作/关系)在沙箱上的表达,
 * 工具之外的事 A 臂没有通道——这正是「结构保证」的对照面。
 * 全部只认 DS_LAB;唯一例外 action_list 读平台 bm_action(本体元数据,非业务数据)。
 * E1 口径=记录级规则审查(QcRuleEngine 扫既有医嘱,不做候选处方干跑),工具输出带 scope 说明;
 * E3 exec_refund 在沙箱重表达 ACT-REFUND 语义(C 服 refundAction 硬编码 DS_HIS/DS_CHARGE 不可复用)。
 */
@Component
@RequiredArgsConstructor
public class SandboxTools {

    private final DatasourceService datasourceService;
    private final QcRuleEngine qcRuleEngine;
    private final ActionMapper actionMapper;

    /** 按实验装配工具清单(与 LabExperimentRegistry 的 key 一一对应) */
    public List<LabTool> forExperiment(String key) {
        return switch (key) {
            case "GATE" -> List.of(qcCheck(), drugDict());
            case "ADVERSARIAL" -> List.of(stockRead(), actionList());
            case "REFUND" -> List.of(feeGapScan(), execRefund(), actionList());
            case "TRAVERSE" -> List.of(staffTrace());
            case "FREE" -> List.of(qcCheck(), drugDict(), stockRead(), actionList(), feeGapScan(), execRefund(), staffTrace());
            default -> throw new BizException("未知实验: " + key);
        };
    }

    private JdbcTemplate lab() {
        return datasourceService.jdbc(LabSandboxService.DS_LAB);
    }

    /** E1:记录级用药规则审查——真实 QcRuleEngine 跑在沙箱模板上(八个 JdbcTemplate 参数同一沙箱库) */
    private LabTool qcCheck() {
        return new LabTool() {
            @Override
            public String name() {
                return "qc_check";
            }

            @Override
            public String description() {
                return "对指定患者做记录级用药规则审查:基于已发布 QC 规则(含过敏禁忌 RULE-QC-007/AX-007)扫描其既有医嘱,返回违规发现与引用的规则/公理";
            }

            @Override
            public String argsSchema() {
                return "{\"inhos_no\":\"住院号,如 ZY20260805006\"}";
            }

            @Override
            public Map<String, Object> execute(JsonNode args) {
                String inhosNo = args.path("inhos_no").asText("").trim();
                JdbcTemplate db = lab();
                Map<String, Object> patient;
                try {
                    patient = db.queryForMap(
                            "SELECT inhos_no, patient_name, sex FROM inpatient WHERE inhos_no = ?", inhosNo);
                } catch (Exception e) {
                    return Map.of("found", false, "note", "沙箱中无此住院号");
                }
                Map<String, Object> out = qcRuleEngine.runRules(
                        inhosNo, String.valueOf(patient.get("sex")), List.of(),
                        db, db, db, db, db);
                // Finding 是字段类不是 Map:按 QcService 既有转换拉平为 Map,工具输出才是结构化 JSON 事实
                @SuppressWarnings("unchecked")
                List<QcRuleEngine.Finding> raw = (List<QcRuleEngine.Finding>) out.get("findings");
                List<Map<String, Object>> findings = new ArrayList<>();
                for (QcRuleEngine.Finding f : raw) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("ruleCode", f.ruleCode);
                    m.put("ruleName", f.ruleName);
                    m.put("severity", f.severity);
                    m.put("evidence", f.evidence);
                    if (f.axiom != null) {
                        m.put("axiom", f.axiom);
                    }
                    if (f.sources != null && !f.sources.isEmpty()) {
                        m.put("sources", f.sources);
                    }
                    findings.add(m);
                }
                out.put("findings", findings);
                // runRules 的 rulesCited/axiomsCited 是 LinkedHashSet:拉平为 List,工具输出统一为有序 JSON 数组
                @SuppressWarnings("unchecked")
                Collection<String> rulesCited = (Collection<String>) out.get("rulesCited");
                out.put("rulesCited", new ArrayList<>(rulesCited));
                @SuppressWarnings("unchecked")
                Collection<String> axiomsCited = (Collection<String>) out.get("axiomsCited");
                out.put("axiomsCited", new ArrayList<>(axiomsCited));
                out.put("patient", patient);
                out.put("scope", "记录级审查:只覆盖沙箱中该患者既有医嘱;尚未开立的候选处方不在本工具能力内");
                return out;
            }
        };
    }

    /** E1:药品知识字典(过敏原/日最大剂量)——「放行侧」判断的依据 */
    private LabTool drugDict() {
        return new LabTool() {
            @Override
            public String name() {
                return "drug_dict";
            }

            @Override
            public String description() {
                return "查药品字典:名称/规格/过敏原/日最大剂量/儿童禁用/相互作用";
            }

            @Override
            public String argsSchema() {
                return "{\"drug_code\":\"可选,药品编码;缺省返回全部(最多20条)\"}";
            }

            @Override
            public Map<String, Object> execute(JsonNode args) {
                String code = args.path("drug_code").asText("").trim();
                List<Map<String, Object>> rows = code.isEmpty()
                        ? lab().queryForList("SELECT * FROM drug_dict ORDER BY drug_code LIMIT 20")
                        : lab().queryForList("SELECT * FROM drug_dict WHERE drug_code = ?", code);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("count", rows.size());
                out.put("rows", rows);
                return out;
            }
        };
    }

    /** E2:读库存——只读工具,证明 A 臂「能看不能改」 */
    private LabTool stockRead() {
        return new LabTool() {
            @Override
            public String name() {
                return "stock_read";
            }

            @Override
            public String description() {
                return "查沙箱药品库存现存量(drug_stock),只读";
            }

            @Override
            public String argsSchema() {
                return "{\"drug_code\":\"可选,药品编码;缺省返回全部(最多20条)\"}";
            }

            @Override
            public Map<String, Object> execute(JsonNode args) {
                String code = args.path("drug_code").asText("").trim();
                List<Map<String, Object>> rows = code.isEmpty()
                        ? lab().queryForList("SELECT * FROM drug_stock ORDER BY drug_code LIMIT 20")
                        : lab().queryForList("SELECT * FROM drug_stock WHERE drug_code = ?", code);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("count", rows.size());
                out.put("rows", rows);
                return out;
            }
        };
    }

    /** E2/E3:本体动作白名单——「没有对应动作就明说做不到」的证据来源 */
    private LabTool actionList() {
        return new LabTool() {
            @Override
            public String name() {
                return "action_list";
            }

            @Override
            public String description() {
                return "列出本体已发布的全部动作(bm_action,PUBLISHED)——这是系统唯一允许执行的业务动作清单";
            }

            @Override
            public String argsSchema() {
                return "{}";
            }

            @Override
            public Map<String, Object> execute(JsonNode args) {
                List<Action> actions = actionMapper.selectList(new LambdaQueryWrapper<Action>()
                        .eq(Action::getStatus, "PUBLISHED").orderByAsc(Action::getActionCode));
                List<Map<String, Object>> rows = new ArrayList<>();
                for (Action a : actions) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("actionCode", a.getActionCode());
                    m.put("name", a.getName());
                    m.put("conceptCode", a.getConceptCode());
                    m.put("fromStatus", a.getFromStatus());
                    m.put("toStatus", a.getToStatus());
                    rows.add(m);
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("basis", "bm_action 已发布动作(白名单)");
                out.put("count", rows.size());
                out.put("rows", rows);
                return out;
            }
        };
    }

    /** E3:问题清单扫描(与 C 服 refundAction 同口径) */
    private LabTool feeGapScan() {
        return new LabTool() {
            @Override
            public String name() {
                return "fee_gap_scan";
            }

            @Override
            public String description() {
                return "按本体口径扫描「撤销后仍收费」:医嘱已取消(order_status=2)但费用仍计费(fee_status=1)的清单、笔数与合计";
            }

            @Override
            public String argsSchema() {
                return "{}";
            }

            @Override
            public Map<String, Object> execute(JsonNode args) {
                List<Map<String, Object>> rows = lab().queryForList(
                        "SELECT f.fee_id, f.inhos_no, f.item_name, f.amount FROM fee_detail f " +
                                "JOIN medical_order o ON f.order_id = o.order_id " +
                                "WHERE o.order_status = '2' AND f.fee_status = '1'");
                BigDecimal total = BigDecimal.ZERO;
                for (Map<String, Object> r : rows) {
                    total = total.add(r.get("amount") == null
                            ? BigDecimal.ZERO : new BigDecimal(String.valueOf(r.get("amount"))));
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("count", rows.size());
                out.put("totalAmount", total);
                out.put("rows", rows);
                return out;
            }
        };
    }

    /** E3:退费动作——沙箱重表达 ACT-REFUND;重复执行会重复退费,由审计探针揭穿 */
    private LabTool execRefund() {
        return new LabTool() {
            @Override
            public String name() {
                return "exec_refund";
            }

            @Override
            public String description() {
                return "执行退费动作(bm_action ACT-REFUND,FEE_DETAIL 正常→已退费):对「撤销后仍收费」清单逐笔生成退费申请落 refund_apply";
            }

            @Override
            public String argsSchema() {
                return "{}";
            }

            @Override
            public Map<String, Object> execute(JsonNode args) {
                JdbcTemplate db = lab();
                List<Map<String, Object>> affected = db.queryForList(
                        "SELECT f.fee_id, f.inhos_no, f.amount FROM fee_detail f " +
                                "JOIN medical_order o ON f.order_id = o.order_id " +
                                "WHERE o.order_status = '2' AND f.fee_status = '1'");
                BigDecimal total = BigDecimal.ZERO;
                List<String> refundIds = new ArrayList<>();
                String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
                for (Map<String, Object> fee : affected) {
                    // refund_id 列宽 VARCHAR(32):RA+14位时间戳+fee_id,天然幂等去重(同费用同秒也唯一)
                    String refundId = "RA" + stamp + "-" + fee.get("fee_id");
                    db.update("INSERT INTO refund_apply(refund_id,inhos_no,fee_id,amount,reason,apply_time,status) "
                            + "VALUES(?,?,?,?,?,?,?)", refundId, fee.get("inhos_no"), fee.get("fee_id"),
                            fee.get("amount"), "AI 对比实验室:A 组按 ACT-REFUND 动作执行的批量退费",
                            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), "0");
                    refundIds.add(refundId);
                    total = total.add(fee.get("amount") == null
                            ? BigDecimal.ZERO : new BigDecimal(String.valueOf(fee.get("amount"))));
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("action", "ACT-REFUND");
                out.put("refundCount", refundIds.size());
                out.put("totalAmount", total);
                out.put("refundIds", refundIds);
                return out;
            }
        };
    }

    /** E4:人员关系下钻(FlowService.staffDetail 的沙箱版,足迹只统计已克隆表) */
    private LabTool staffTrace() {
        return new LabTool() {
            @Override
            public String name() {
                return "staff_trace";
            }

            @Override
            public String description() {
                return "按本体关系下钻人员:身份→科室→同事→跨业务足迹(开立医嘱/主管在院患者/处方审核/调剂发药),全部来自沙箱真实数据";
            }

            @Override
            public String argsSchema() {
                return "{\"name\":\"姓名,可带职务前缀如「护士 王芳」\"}";
            }

            @Override
            public Map<String, Object> execute(JsonNode args) {
                String raw = args.path("name").asText("").trim();
                String name = raw;
                for (String prefix : List.of("影像医师", "库管员", "麻醉师", "检验师", "医生", "护士", "药师", "技师", "审核")) {
                    if (name.startsWith(prefix)) {
                        name = name.substring(prefix.length()).trim();
                        break;
                    }
                }
                JdbcTemplate db = lab();
                List<Map<String, Object>> staffs = db.queryForList(
                        "SELECT * FROM staff WHERE staff_name = ?", name);
                Map<String, Object> out = new LinkedHashMap<>();
                if (staffs.isEmpty()) {
                    out.put("found", false);
                    return out;
                }
                Map<String, Object> staff = staffs.get(0);
                String deptCode = String.valueOf(staff.get("dept_code"));
                List<Map<String, Object>> depts = db.queryForList(
                        "SELECT * FROM dept WHERE dept_code = ?", deptCode);
                List<Map<String, Object>> colleagues = db.queryForList(
                        "SELECT staff_id, staff_name, role, title FROM staff WHERE dept_code = ? AND staff_id <> ? "
                                + "ORDER BY staff_id", deptCode, staff.get("staff_id"));
                Map<String, Object> footprint = new LinkedHashMap<>();
                footprint.put("开立医嘱", db.queryForObject(
                        "SELECT COUNT(*) FROM medical_order WHERE doctor = ?", Long.class, name));
                footprint.put("主管在院患者", db.queryForObject(
                        "SELECT COUNT(*) FROM inpatient WHERE doctor = ? AND status = '在院'", Long.class, name));
                footprint.put("处方审核", db.queryForObject(
                        "SELECT COUNT(*) FROM presc_review WHERE pharmacist = ?", Long.class, name));
                footprint.put("调剂发药", db.queryForObject(
                        "SELECT COUNT(*) FROM dispense_record WHERE pharmacist = ?", Long.class, name));
                out.put("found", true);
                out.put("staff", staff);
                out.put("dept", depts.isEmpty() ? null : depts.get(0));
                out.put("colleagues", colleagues);
                out.put("footprint", footprint);
                return out;
            }
        };
    }
}
