package com.bemodel.flow;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bemodel.datasource.entity.Mapping;
import com.bemodel.datasource.event.MappingChangedEvent;
import com.bemodel.datasource.mapper.MappingMapper;
import com.bemodel.datasource.service.DatasourceService;
import com.bemodel.common.BizException;
import com.bemodel.common.Masking;
import com.bemodel.common.PageResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 医嘱全闭环装配：以住院号为轴，跨 HIS/LIS/收费/药房 四个产品库
 * 把割裂的物理数据还原成完整业务闭环。状态码翻译直接复用本平台的
 * 「物理列-概念属性值字典映射」——平台自身就是口径统一的消费方。
 */
@Service
@RequiredArgsConstructor
public class FlowService {

    private final DatasourceService datasourceService;
    private final MappingMapper mappingMapper;
    private final ObjectMapper objectMapper;

    /** 患者列表（含费用/医嘱汇总） */
    public PageResult<Map<String, Object>> patients(String keyword, int pageNum, int pageSize) {
        JdbcTemplate his = datasourceService.jdbc("DS_HIS");
        String where = "WHERE 1=1 ";
        List<Object> params = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where += "AND (patient_name LIKE ? OR inhos_no LIKE ?) ";
            params.add("%" + keyword + "%");
            params.add("%" + keyword + "%");
        }
        Long total = his.queryForObject("SELECT COUNT(*) FROM inpatient " + where, Long.class, params.toArray());
        List<Map<String, Object>> visits = Masking.maskPatientRows(his.queryForList(
                "SELECT * FROM inpatient " + where + "ORDER BY admit_time DESC LIMIT " + pageSize
                        + " OFFSET " + (pageNum - 1) * pageSize, params.toArray()));
        for (Map<String, Object> v : visits) {
            String inhosNo = String.valueOf(v.get("inhos_no"));
            Map<String, Object> agg = his.queryForMap(
                    "SELECT COUNT(DISTINCT o.order_id) AS order_cnt, " +
                            "IFNULL((SELECT SUM(f.amount) FROM fee_detail f WHERE f.inhos_no = ? AND f.fee_status='1'),0) AS fee_total " +
                            "FROM medical_order o WHERE o.inhos_no = ?", inhosNo, inhosNo);
            v.put("orderCount", agg.get("order_cnt"));
            v.put("feeTotal", agg.get("fee_total"));
        }
        return PageResult.of(visits, total == null ? 0 : total, pageNum, pageSize);
    }

    /** 单个住院就诊的医嘱全闭环 */
    public Map<String, Object> loop(String inhosNo) {
        JdbcTemplate his = datasourceService.jdbc("DS_HIS");
        JdbcTemplate lis = datasourceService.jdbc("DS_LIS");
        JdbcTemplate charge = datasourceService.jdbc("DS_CHARGE");
        JdbcTemplate pharmacy = datasourceService.jdbc("DS_PHARMACY");
        JdbcTemplate pacs = datasourceService.jdbc("DS_PACS");
        JdbcTemplate nurse = datasourceService.jdbc("DS_NURSE");

        List<Map<String, Object>> visits = his.queryForList(
                "SELECT * FROM inpatient WHERE inhos_no = ?", inhosNo);
        if (visits.isEmpty()) {
            throw new BizException("住院就诊不存在: " + inhosNo);
        }
        Map<String, Object> visit = Masking.maskPatientRow(visits.get(0));

        List<Map<String, Object>> orders = his.queryForList(
                "SELECT * FROM medical_order WHERE inhos_no = ? ORDER BY create_time", inhosNo);

        List<Map<String, Object>> orderLoops = new ArrayList<>();
        List<Map<String, Object>> timeline = new ArrayList<>();
        timeline.add(event(visit.get("admit_time"), "HIS", "入院登记",
                visit.get("dept") + " / " + visit.get("ward") + "，主管医生 " + visit.get("doctor"),
                String.valueOf(visit.get("doctor"))));

        for (Map<String, Object> o : orders) {
            String orderId = String.valueOf(o.get("order_id"));
            Map<String, Object> loop = new LinkedHashMap<>();
            loop.put("orderId", orderId);
            loop.put("itemCode", o.get("item_code"));
            loop.put("itemName", o.get("item_name"));
            loop.put("orderType", o.get("order_type"));
            loop.put("orderStatus", o.get("order_status"));
            loop.put("orderStatusName", decode("DS_HIS", "medical_order", "order_status", o.get("order_status")));
            loop.put("doctor", o.get("doctor"));
            loop.put("createTime", o.get("create_time"));
            loop.put("cancelTime", o.get("cancel_time"));

            timeline.add(event(o.get("create_time"), "HIS", "医嘱开立",
                    o.get("order_type") + "｜" + o.get("item_name") + "｜" + o.get("doctor"),
                    String.valueOf(o.get("doctor"))));

            // 计费环节
            List<Map<String, Object>> fees = his.queryForList(
                    "SELECT * FROM fee_detail WHERE order_id = ?", orderId);
            Map<String, Object> fee = null;
            if (!fees.isEmpty()) {
                fee = fees.get(0);
                fee.put("feeStatusName", decode("DS_HIS", "fee_detail", "fee_status", fee.get("fee_status")));
                timeline.add(event(fee.get("charge_time"), "HIS", "费用计费",
                        o.get("item_name") + " ¥" + fee.get("amount")));
                loop.put("fee", fee);
            }

            // 检验环节（LIS）
            List<Map<String, Object>> applies = lis.queryForList(
                    "SELECT * FROM lab_apply WHERE order_id = ?", orderId);
            if (!applies.isEmpty()) {
                Map<String, Object> apply = applies.get(0);
                apply.put("statusName", decode("DS_LIS", "lab_apply", "apply_status", apply.get("apply_status")));
                loop.put("labApply", apply);
                timeline.add(event(apply.get("apply_time"), "LIS", "检验申请", String.valueOf(apply.get("item_name"))));
                if (!Objects.equals(String.valueOf(apply.get("apply_status")), "N")) {
                    timeline.add(event(apply.get("update_time"), "LIS",
                            "申请" + apply.get("statusName"), "申请号 " + apply.get("apply_id")));
                }
                List<Map<String, Object>> reports = lis.queryForList(
                        "SELECT * FROM lab_report WHERE apply_id = ?", apply.get("apply_id"));
                if (!reports.isEmpty()) {
                    Map<String, Object> report = reports.get(0);
                    report.put("statusName", decode("DS_LIS", "lab_report", "result_status", report.get("result_status")));
                    loop.put("labReport", report);
                    timeline.add(event(report.get("report_time"), "LIS", "检验报告发布",
                            o.get("item_name") + "｜结果" + report.get("statusName") + "｜" + report.get("reporter"),
                            String.valueOf(report.get("reporter"))));
                }
            }

            // 药房环节（PHARMACY）：处方审核（闭环必经环节）→ 调剂发药
            List<Map<String, Object>> reviews = pharmacy.queryForList(
                    "SELECT * FROM presc_review WHERE order_id = ?", orderId);
            if (!reviews.isEmpty()) {
                Map<String, Object> review = reviews.get(0);
                loop.put("prescReview", review);
                timeline.add(event(review.get("review_time"), "药房", "处方审核" + review.get("review_result"),
                        o.get("item_name") + "｜药师 " + review.get("pharmacist")
                                + ("驳回".equals(String.valueOf(review.get("review_result")))
                                        ? "｜" + review.get("reject_reason") : ""),
                        String.valueOf(review.get("pharmacist"))));
            }
            List<Map<String, Object>> dispenses = pharmacy.queryForList(
                    "SELECT * FROM dispense_record WHERE order_id = ?", orderId);
            if (!dispenses.isEmpty()) {
                Map<String, Object> dispense = dispenses.get(0);
                dispense.put("statusName", decode("DS_PHARMACY", "dispense_record", "status", dispense.get("status")));
                loop.put("dispense", dispense);
                timeline.add(event(dispense.get("dispense_time"), "药房",
                        String.valueOf(dispense.get("statusName")),
                        o.get("item_name") + "｜药师 " + dispense.get("pharmacist"),
                        String.valueOf(dispense.get("pharmacist"))));
            }

            // 检查环节（PACS）
            List<Map<String, Object>> exams = pacs.queryForList(
                    "SELECT * FROM exam_report WHERE order_id = ?", orderId);
            if (!exams.isEmpty()) {
                Map<String, Object> exam = exams.get(0);
                exam.put("abnormalName", decode("DS_PACS", "exam_report", "abnormal_flag", exam.get("abnormal_flag")));
                loop.put("examReport", exam);
                timeline.add(event(exam.get("exam_time"), "PACS", "检查执行",
                        o.get("item_name") + "｜技师 " + exam.get("technician"),
                        String.valueOf(exam.get("technician"))));
                timeline.add(event(exam.get("report_time"), "PACS", "检查报告发布",
                        exam.get("abnormalName") + "｜" + exam.get("conclusion") + "｜审核 " + exam.get("reviewer"),
                        String.valueOf(exam.get("reviewer"))));
            }

            // 护士执行确认环节（NURSE）
            List<Map<String, Object>> execs = nurse.queryForList(
                    "SELECT * FROM nurse_exec WHERE order_id = ?", orderId);
            if (!execs.isEmpty()) {
                Map<String, Object> exec = execs.get(0);
                exec.put("execStatusName", decode("DS_NURSE", "nurse_exec", "exec_status", exec.get("exec_status")));
                loop.put("nurseExec", exec);
                timeline.add(event(exec.get("exec_time"), "护士站", "执行确认",
                        exec.get("exec_type") + "｜" + exec.get("nurse"),
                        String.valueOf(exec.get("nurse"))));
            }

            if (o.get("cancel_time") != null) {
                timeline.add(event(o.get("cancel_time"), "HIS", "医嘱取消", String.valueOf(o.get("item_name"))));
            }

            loop.put("loopStatus", loopStatus(o, fee, loop.get("labReport"), loop.get("dispense"), loop.get("examReport")));
            loop.put("loopStatusName", loopStatusName(String.valueOf(loop.get("loopStatus"))));
            orderLoops.add(loop);
        }

        // 缴费与结算
        List<Map<String, Object>> payments = charge.queryForList(
                "SELECT * FROM pay_record WHERE inhos_no = ? ORDER BY pay_time", inhosNo);
        for (Map<String, Object> p : payments) {
            p.put("payTypeName", decode("DS_CHARGE", "pay_record", "pay_type", p.get("pay_type")));
            timeline.add(event(p.get("pay_time"), "收费", String.valueOf(p.get("payTypeName")),
                    "¥" + p.get("amount") + "｜" + p.get("channel")));
        }
        List<Map<String, Object>> settlements = charge.queryForList(
                "SELECT * FROM settlement WHERE inhos_no = ?", inhosNo);
        Map<String, Object> settlement = settlements.isEmpty() ? null : settlements.get(0);
        if (settlement != null) {
            timeline.add(event(settlement.get("settle_time"), "收费", "出院结算", "总额 ¥" + settlement.get("total_amount")));
        }
        if (visit.get("discharge_time") != null) {
            timeline.add(event(visit.get("discharge_time"), "HIS", "出院", ""));
        }
        timeline.sort(Comparator.comparing(e -> String.valueOf(e.get("time"))));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("visit", visit);
        result.put("orders", orderLoops);
        result.put("payments", payments);
        result.put("settlement", settlement);
        result.put("timeline", timeline);
        return result;
    }

    // ==================== 人事组织域下钻：流程页姓名 → 人员/科室主数据 ====================

    /**
     * 人员下钻：医嘱/执行/审核/发药里的姓名（可带"医生/护士/药师"等口语前缀）
     * 对齐人事组织域主数据，返回人员档案、所属科室、同科室同事与跨库业务足迹。
     * 姓名未纳入主数据时 found=false——主数据覆盖缺口本身也是治理发现。
     */
    public Map<String, Object> staffDetail(String rawName) {
        JdbcTemplate his = datasourceService.jdbc("DS_HIS");
        String name = rawName == null ? "" : rawName.trim();
        for (String prefix : List.of("影像医师", "库管员", "麻醉师", "检验师", "医生", "护士", "药师", "技师", "审核")) {
            if (name.startsWith(prefix)) {
                name = name.substring(prefix.length()).trim();
                break;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("queryName", rawName);
        List<Map<String, Object>> staffs = his.queryForList(
                "SELECT * FROM staff WHERE staff_name = ?", name);
        if (staffs.isEmpty()) {
            result.put("found", false);
            return result;
        }
        Map<String, Object> staff = staffs.get(0);
        String deptCode = String.valueOf(staff.get("dept_code"));
        List<Map<String, Object>> depts = his.queryForList(
                "SELECT * FROM dept WHERE dept_code = ?", deptCode);
        List<Map<String, Object>> colleagues = his.queryForList(
                "SELECT staff_id, staff_name, role, title FROM staff WHERE dept_code = ? AND staff_id <> ? "
                        + "ORDER BY staff_id", deptCode, staff.get("staff_id"));

        // 跨库业务足迹：主数据姓名与各产品库字符串的责任主体对齐（本体关系"由谁开立/由谁执行"的实例化）
        JdbcTemplate nurse = datasourceService.jdbc("DS_NURSE");
        JdbcTemplate pharmacy = datasourceService.jdbc("DS_PHARMACY");
        Map<String, Object> footprint = new LinkedHashMap<>();
        footprint.put("开立医嘱", his.queryForObject(
                "SELECT COUNT(*) FROM medical_order WHERE doctor = ?", Long.class, name));
        footprint.put("主管在院患者", his.queryForObject(
                "SELECT COUNT(*) FROM inpatient WHERE doctor = ? AND status = '在院'", Long.class, name));
        footprint.put("执行确认", nurse.queryForObject(
                "SELECT COUNT(*) FROM nurse_exec WHERE nurse LIKE ?", Long.class, "%" + name));
        footprint.put("处方审核", pharmacy.queryForObject(
                "SELECT COUNT(*) FROM presc_review WHERE pharmacist = ?", Long.class, name));
        footprint.put("调剂发药", pharmacy.queryForObject(
                "SELECT COUNT(*) FROM dispense_record WHERE pharmacist = ?", Long.class, name));

        result.put("found", true);
        result.put("staff", staff);
        result.put("dept", depts.isEmpty() ? null : depts.get(0));
        result.put("colleagues", colleagues);
        result.put("footprint", footprint);
        return result;
    }

    // ==================== 门诊闭环（同一框架平移到处方线） ====================

    /** 门诊患者列表（挂号+看诊+处方汇总），服务端分页 */
    public PageResult<Map<String, Object>> opdPatients(String keyword, int pageNum, int pageSize) {
        JdbcTemplate opd = datasourceService.jdbc("DS_OPD");
        String where = "WHERE 1=1 ";
        List<Object> params = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where += "AND (r.pat_name LIKE ? OR r.pat_card_no LIKE ?) ";
            params.add("%" + keyword + "%");
            params.add("%" + keyword + "%");
        }
        Long total = opd.queryForObject("SELECT COUNT(*) FROM opd_reg r " + where, Long.class, params.toArray());
        List<Map<String, Object>> regs = Masking.maskPatientRows(opd.queryForList(
                "SELECT r.*, v.visit_id, v.diag, v.status AS visit_status FROM opd_reg r " +
                        "LEFT JOIN opd_visit v ON v.card_no = r.pat_card_no " + where + "ORDER BY r.reg_time DESC LIMIT "
                        + pageSize + " OFFSET " + (pageNum - 1) * pageSize, params.toArray()));
        for (Map<String, Object> r : regs) {
            Map<String, Object> agg = opd.queryForMap(
                    "SELECT COUNT(*) AS presc_cnt, IFNULL(SUM(CASE WHEN presc_status='3' THEN price END),0) AS exec_total " +
                            "FROM opd_presc WHERE card_no = ?", r.get("pat_card_no"));
            r.put("prescCount", agg.get("presc_cnt"));
            r.put("execTotal", agg.get("exec_total"));
            r.put("regStatusName", decode("DS_OPD", "opd_reg", "reg_status", r.get("reg_status")));
        }
        return PageResult.of(regs, total == null ? 0 : total, pageNum, pageSize);
    }

    /** 单个门诊就诊卡的全闭环：挂号 → 看诊 → 处方 → 缴费 → 执行（LIS/药房/PACS） */
    public Map<String, Object> opdLoop(String cardNo) {
        JdbcTemplate opd = datasourceService.jdbc("DS_OPD");
        JdbcTemplate lis = datasourceService.jdbc("DS_LIS");
        JdbcTemplate charge = datasourceService.jdbc("DS_CHARGE");
        JdbcTemplate pharmacy = datasourceService.jdbc("DS_PHARMACY");
        JdbcTemplate pacs = datasourceService.jdbc("DS_PACS");

        List<Map<String, Object>> regs = opd.queryForList(
                "SELECT * FROM opd_reg WHERE pat_card_no = ?", cardNo);
        if (regs.isEmpty()) {
            throw new BizException("就诊卡号不存在: " + cardNo);
        }
        Map<String, Object> reg = Masking.maskPatientRow(regs.get(0));
        reg.put("regStatusName", decode("DS_OPD", "opd_reg", "reg_status", reg.get("reg_status")));

        List<Map<String, Object>> timeline = new ArrayList<>();
        timeline.add(event(reg.get("reg_time"), "门诊", "挂号",
                reg.get("reg_dept") + "｜" + reg.get("reg_doctor") + "｜挂号费 ¥" + reg.get("reg_fee"),
                String.valueOf(reg.get("reg_doctor"))));
        if ("2".equals(String.valueOf(reg.get("reg_status")))) {
            timeline.add(event(reg.get("reg_time"), "门诊", "退号", "挂号费原路退回"));
        }

        List<Map<String, Object>> visits = opd.queryForList(
                "SELECT * FROM opd_visit WHERE card_no = ?", cardNo);
        Map<String, Object> visit = visits.isEmpty() ? null : Masking.maskPatientRow(visits.get(0));
        if (visit != null) {
            visit.put("statusName", decode("DS_OPD", "opd_visit", "status", visit.get("status")));
            timeline.add(event(visit.get("visit_time"), "门诊", "看诊",
                    "诊断：" + visit.get("diag") + "｜" + visit.get("doctor"),
                    String.valueOf(visit.get("doctor"))));
        }

        List<Map<String, Object>> prescs = opd.queryForList(
                "SELECT * FROM opd_presc WHERE card_no = ? ORDER BY create_time", cardNo);
        List<Map<String, Object>> prescLoops = new ArrayList<>();
        for (Map<String, Object> p : prescs) {
            Map<String, Object> loop = new LinkedHashMap<>();
            String prescId = String.valueOf(p.get("presc_id"));
            loop.put("prescId", prescId);
            loop.put("itemCode", p.get("item_code"));
            loop.put("itemName", p.get("item_name"));
            loop.put("itemType", p.get("item_type"));
            loop.put("quantity", p.get("quantity"));
            loop.put("price", p.get("price"));
            loop.put("prescStatus", p.get("presc_status"));
            loop.put("statusName", decode("DS_OPD", "opd_presc", "presc_status", p.get("presc_status")));
            loop.put("createTime", p.get("create_time"));
            timeline.add(event(p.get("create_time"), "门诊", "处方开立",
                    p.get("item_type") + "｜" + p.get("item_name") + " ×" + p.get("quantity")));

            // 执行环节（处方号复用为执行系统的关联号）
            List<Map<String, Object>> applies = lis.queryForList(
                    "SELECT * FROM lab_apply WHERE order_id = ?", prescId);
            if (!applies.isEmpty()) {
                Map<String, Object> apply = applies.get(0);
                apply.put("statusName", decode("DS_LIS", "lab_apply", "apply_status", apply.get("apply_status")));
                loop.put("labApply", apply);
                timeline.add(event(apply.get("update_time"), "LIS", "检验" + apply.get("statusName"),
                        String.valueOf(apply.get("item_name"))));
                List<Map<String, Object>> reports = lis.queryForList(
                        "SELECT * FROM lab_report WHERE apply_id = ?", apply.get("apply_id"));
                if (!reports.isEmpty()) {
                    loop.put("labReport", reports.get(0));
                    timeline.add(event(reports.get(0).get("report_time"), "LIS", "检验报告发布",
                            String.valueOf(p.get("item_name"))));
                }
            }
            List<Map<String, Object>> dispenses = pharmacy.queryForList(
                    "SELECT * FROM dispense_record WHERE order_id = ?", prescId);
            if (!dispenses.isEmpty()) {
                Map<String, Object> dispense = dispenses.get(0);
                dispense.put("statusName", decode("DS_PHARMACY", "dispense_record", "status", dispense.get("status")));
                loop.put("dispense", dispense);
                timeline.add(event(dispense.get("dispense_time"), "药房",
                        String.valueOf(dispense.get("statusName")),
                        p.get("item_name") + "｜药师 " + dispense.get("pharmacist"),
                        String.valueOf(dispense.get("pharmacist"))));
            }
            // 检查环节（PACS）
            List<Map<String, Object>> exams = pacs.queryForList(
                    "SELECT * FROM exam_report WHERE order_id = ?", prescId);
            if (!exams.isEmpty()) {
                Map<String, Object> exam = exams.get(0);
                exam.put("abnormalName", decode("DS_PACS", "exam_report", "abnormal_flag", exam.get("abnormal_flag")));
                loop.put("examReport", exam);
                timeline.add(event(exam.get("report_time"), "PACS", "检查报告发布",
                        exam.get("abnormalName") + "｜" + exam.get("conclusion")));
            }

            // 闭环状态（处方口径）：作废/未缴费/已缴费待执行/已执行闭环
            String ps = String.valueOf(p.get("presc_status"));
            String loopStatus = switch (ps) {
                case "0" -> "CLOSED_CANCELED";
                case "3" -> "CLOSED";
                case "2" -> "PENDING";
                default -> "UNPAID";
            };
            loop.put("loopStatus", loopStatus);
            loop.put("loopStatusName", switch (loopStatus) {
                case "CLOSED_CANCELED" -> "已作废闭环";
                case "CLOSED" -> "已闭环";
                case "PENDING" -> "已缴费待执行";
                default -> "待缴费";
            });
            prescLoops.add(loop);
        }

        // 门诊缴费
        List<Map<String, Object>> payments = charge.queryForList(
                "SELECT * FROM pay_record WHERE inhos_no = ? ORDER BY pay_time", cardNo);
        for (Map<String, Object> pay : payments) {
            pay.put("payTypeName", decode("DS_CHARGE", "pay_record", "pay_type", pay.get("pay_type")));
            timeline.add(event(pay.get("pay_time"), "收费", String.valueOf(pay.get("payTypeName")),
                    "¥" + pay.get("amount") + "｜" + pay.get("channel")));
        }
        timeline.sort(Comparator.comparing(e -> String.valueOf(e.get("time"))));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("register", reg);
        result.put("visit", visit);
        result.put("prescriptions", prescLoops);
        result.put("payments", payments);
        result.put("timeline", timeline);
        return result;
    }

    /** 闭环状态：BROKEN 异常中断（取消未退费）/ CLOSED_CANCELED 取消闭环 / CLOSED 已闭环 / PENDING 进行中 */
    private String loopStatus(Map<String, Object> order, Map<String, Object> fee,
                              Object labReport, Object dispense) {
        return loopStatus(order, fee, labReport, dispense, null);
    }

    private String loopStatus(Map<String, Object> order, Map<String, Object> fee,
                              Object labReport, Object dispense, Object examReport) {
        String orderStatus = String.valueOf(order.get("order_status"));
        if ("2".equals(orderStatus)) {
            boolean refunded = fee != null && "2".equals(String.valueOf(fee.get("fee_status")));
            return refunded ? "CLOSED_CANCELED" : "BROKEN";
        }
        if ("0".equals(orderStatus)) {
            return "PENDING";
        }
        boolean executed = labReport != null
                || examReport != null
                || (dispense != null && "已发药".equals(((Map<?, ?>) dispense).get("statusName")));
        return executed && fee != null ? "CLOSED" : "PENDING";
    }

    private String loopStatusName(String status) {
        return switch (status) {
            case "BROKEN" -> "异常中断";
            case "CLOSED_CANCELED" -> "已取消闭环";
            case "CLOSED" -> "已闭环";
            default -> "进行中";
        };
    }

    private Map<String, Object> event(Object time, String system, String event, String detail) {
        return event(time, system, event, detail, null);
    }

    /** staff：事件中出现的责任人姓名（可带角色前缀），前端据以下钻到人事主数据 */
    private Map<String, Object> event(Object time, String system, String event, String detail, String staff) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("time", time);
        e.put("system", system);
        e.put("event", event);
        e.put("detail", detail);
        if (staff != null && !staff.isBlank()) {
            e.put("staff", staff);
        }
        return e;
    }

    /** 用平台映射元数据中的值字典，把物理状态码翻译成标准口径 */
    private final Map<String, Map<String, String>> valueMapCache = new HashMap<>();

    /**
     * 映射生命周期/编辑/删除后失效值字典缓存：没有这一步，ACTIVE 门禁会被永不失效的缓存架空——
     * 停用后流程运行时仍按旧字典翻译，PROPOSED 期间查过的列也永远进不来新生效映射。
     */
    @EventListener
    public void onMappingChanged(MappingChangedEvent e) {
        valueMapCache.clear();
    }

    private String decode(String dsCode, String table, String column, Object raw) {
        if (raw == null) {
            return "-";
        }
        String key = dsCode + "." + table + "." + column;
        Map<String, String> dict = valueMapCache.computeIfAbsent(key, k -> {
            Mapping m = mappingMapper.selectOne(new LambdaQueryWrapper<Mapping>()
                    .eq(Mapping::getDsCode, dsCode)
                    .eq(Mapping::getTableName, table)
                    .eq(Mapping::getColumnName, column)
                    .eq(Mapping::getStatus, "ACTIVE") // 流程运行时只消费生效映射（V30）
                    .last("LIMIT 1"));
            if (m == null || m.getValueMap() == null) {
                return Map.of();
            }
            try {
                Map<String, String> parsed = new HashMap<>();
                JsonNode node = objectMapper.readTree(m.getValueMap());
                node.fields().forEachRemaining(f -> parsed.put(f.getKey(), f.getValue().asText()));
                return parsed;
            } catch (Exception e) {
                return Map.of();
            }
        });
        return dict.getOrDefault(String.valueOf(raw), String.valueOf(raw));
    }
}
