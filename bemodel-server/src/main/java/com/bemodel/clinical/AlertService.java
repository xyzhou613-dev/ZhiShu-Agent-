package com.bemodel.clinical;

import com.bemodel.common.Masking;
import com.bemodel.datasource.service.DatasourceService;
import com.bemodel.link.entity.LinkNode;
import com.bemodel.link.service.LinkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 危重症预警：检验报告异常（A）且病案无对应诊断、无处置医嘱 → 自动生成 ALERT 预警节点。
 * 判定依据公理 AX-004（检验报告支撑诊断）与规则 RULE-QC-003，预警进入链路追溯体系统一处置。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private static final List<String> COVER_KEYWORDS = List.of("感染", "肺炎", "脓毒", "炎");

    private final DatasourceService datasourceService;
    private final LinkService linkService;

    public Map<String, Object> detect() {
        JdbcTemplate lis = datasourceService.jdbc("DS_LIS");
        JdbcTemplate his = datasourceService.jdbc("DS_HIS");
        JdbcTemplate emr = datasourceService.jdbc("DS_EMR");

        List<Map<String, Object>> abnormals = lis.queryForList(
                "SELECT r.report_id, r.patient_no, r.item_code, r.report_time, a.item_name " +
                        "FROM lab_report r JOIN lab_apply a ON r.apply_id = a.apply_id " +
                        "WHERE r.result_status = 'A'");

        List<Map<String, Object>> created = new ArrayList<>();
        int handled = 0;
        for (Map<String, Object> ab : abnormals) {
            String inhosNo = String.valueOf(ab.get("patient_no"));
            // 覆盖判定：病案诊断命中感染类关键词，或存在已执行抗生素医嘱
            List<Map<String, Object>> emrRows = emr.queryForList(
                    "SELECT diag_list FROM emr_record WHERE inhos_no = ?", inhosNo);
            boolean coveredByDiag = emrRows.stream()
                    .flatMap(r -> Arrays.stream(String.valueOf(r.get("diag_list")).split("[，,]")))
                    .anyMatch(d -> COVER_KEYWORDS.stream().anyMatch(d::contains));
            Integer antibiotics = his.queryForObject(
                    "SELECT COUNT(*) FROM medical_order WHERE inhos_no = ? AND order_status = '1' " +
                            "AND item_code IN ('D006','D011')", Integer.class, inhosNo);
            boolean coveredByDrug = antibiotics != null && antibiotics > 0;

            if (coveredByDiag || coveredByDrug) {
                handled++;
                continue;
            }
            String refNo = "ALERT-" + ab.get("report_id");
            if (linkService.getByRefNo(refNo) != null) {
                continue; // 幂等：同一报告不重复预警
            }
            Map<String, Object> patient = his.queryForMap(
                    "SELECT patient_name, dept, ward FROM inpatient WHERE inhos_no = ?", inhosNo);
            String patientName = Masking.maskName(String.valueOf(patient.get("patient_name")));
            LinkNode alert = new LinkNode();
            alert.setNodeType("ALERT");
            alert.setRefNo(refNo);
            alert.setTitle(String.format("危急值预警：%s %s 检验结果异常未处置",
                    patientName, ab.get("item_name")));
            alert.setConceptCode("LAB_REPORT");
            alert.setStatus("待处置");
            alert.setOccurredAt(LocalDateTime.now());
            alert.setPayload(String.format(
                    "{\"patient\":\"%s\",\"inhos_no\":\"%s\",\"dept\":\"%s\",\"item\":\"%s\",\"report_id\":\"%s\",\"report_time\":\"%s\",\"basis\":\"RULE-QC-003/AX-004\",\"suggestion\":\"请 %s 立即核实患者状态并补录诊断或处置医嘱\"}",
                    patientName, inhosNo, patient.get("dept"), ab.get("item_name"),
                    ab.get("report_id"), ab.get("report_time"), patient.get("dept")));
            linkService.save(alert);
            created.add(Map.of("refNo", refNo, "title", alert.getTitle()));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("abnormalReports", abnormals.size());
        result.put("handled", handled);
        result.put("createdCount", created.size());
        result.put("created", created);
        return result;
    }
}
