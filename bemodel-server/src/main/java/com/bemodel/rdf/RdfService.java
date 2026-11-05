package com.bemodel.rdf;

import com.bemodel.common.BizException;
import com.bemodel.datasource.service.DatasourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * ABox 导出：把某患者跨库的真实数据，按顶层本体模板（docs/ontology-top-template-owl-shacl.md）
 * 导出为 Turtle 三元组，可直接加载到 Jena/RDF4J 跑 SHACL 处方审核。
 * 这是「本体层—数据映射层」的产物：物理数据不经搬运，直接投影为本体实例。
 */
@Service
@RequiredArgsConstructor
public class RdfService {

    private final DatasourceService datasourceService;

    /**
     * 导出患者 ABox。mask=true（默认）时患者姓名脱敏（张*三格式），
     * mask=false 保留原名供调试（仅内网演示，勿用于外发）。
     */
    public String exportPatient(String inhosNo) {
        return exportPatient(inhosNo, true);
    }

    public String exportPatient(String inhosNo, boolean mask) {
        JdbcTemplate his = datasourceService.jdbc("DS_HIS");
        JdbcTemplate emr = datasourceService.jdbc("DS_EMR");
        JdbcTemplate lis = datasourceService.jdbc("DS_LIS");
        JdbcTemplate pharmacy = datasourceService.jdbc("DS_PHARMACY");

        List<Map<String, Object>> patients = his.queryForList(
                "SELECT * FROM inpatient WHERE inhos_no = ?", inhosNo);
        if (patients.isEmpty()) {
            throw new BizException("患者不存在: " + inhosNo);
        }
        Map<String, Object> p = patients.get(0);
        String patientName = mask ? maskName(String.valueOf(p.get("patient_name")))
                : String.valueOf(p.get("patient_name"));

        StringBuilder sb = new StringBuilder();
        sb.append("@prefix med:  <http://bemodel.com/ontology/med#> .\n");
        sb.append("@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n");
        sb.append("@prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .\n\n");
        sb.append("# ===== 患者 ABox（由 BeModel 实例装配器从产品库实时投影生成） =====\n");
        sb.append("# 患者：").append(patientName).append(" 住院号：").append(inhosNo)
                .append(" 导出时间：").append(java.time.LocalDateTime.now()).append('\n');
        sb.append("# 覆盖 SHACL 输入：Shape1 过敏禁忌 / Shape2 剂量上限 / Shape3 儿童禁用 / Shape4 性别互斥 / Shape5 相互作用 / Shape6 审核闭环\n\n");

        String patientUri = "med:Patient_" + inhosNo;
        String encUri = "med:InpEncounter_" + inhosNo;
        sb.append(patientUri).append(" a med:Patient ;\n")
                .append("   rdfs:label \"").append(patientName).append("\" ;\n")
                .append("   med:sex \"").append(p.get("sex")).append("\" ;\n")
                .append("   med:age \"").append(p.get("age")).append("\"^^xsd:integer ;\n")
                .append("   med:hasEncounter ").append(encUri).append(" .\n\n");
        sb.append(encUri).append(" a med:InpEncounter ;\n")
                .append("   rdfs:label \"住院就诊 ").append(inhosNo).append("\" .\n");
        // 类型物化：InpEncounter 是 Encounter 的子类（见 OWL 模板），
        // SHACL 的 sh:targetClass med:Encounter（如 Shape 5 相互作用）需要显式父类型才能命中
        sb.append(encUri).append(" a med:Encounter .\n\n");

        // 过敏史（EMR）→ Shape 1 过敏禁忌的实例级输入
        List<Map<String, Object>> allergies = emr.queryForList(
                "SELECT * FROM patient_allergy WHERE inhos_no = ?", inhosNo);
        for (Map<String, Object> a : allergies) {
            String allergenUri = "med:Allergen_" + a.get("allergen");
            sb.append(allergenUri).append(" a med:Allergen ; rdfs:label \"").append(a.get("allergen")).append("\" .\n");
            sb.append(patientUri).append(" med:hasAllergyTo ").append(allergenUri).append(" .\n\n");
        }

        // 药品知识字典（药房）→ Shape 2 剂量上限 / Shape 1 含过敏原 的知识源
        Map<String, Map<String, Object>> drugDict = new java.util.HashMap<>();
        for (Map<String, Object> d : pharmacy.queryForList("SELECT * FROM drug_dict")) {
            drugDict.put(String.valueOf(d.get("drug_code")), d);
        }

        // 诊断（EMR 病案 + 诊断字典的标准编码）
        List<Map<String, Object>> records = emr.queryForList(
                "SELECT * FROM emr_record WHERE inhos_no = ?", inhosNo);
        int diagSeq = 0;
        for (Map<String, Object> r : records) {
            for (String diagName : String.valueOf(r.get("diag_list")).split("[，,]")) {
                diagName = diagName.trim();
                if (diagName.isEmpty()) {
                    continue;
                }
                String diagUri = "med:Diagnosis_" + inhosNo + "_" + (++diagSeq);
                List<Map<String, Object>> dict = emr.queryForList(
                        "SELECT snomed_code, icd10 FROM diag_dict WHERE diag_name = ?", diagName);
                sb.append(diagUri).append(" a med:Diagnosis ;\n   rdfs:label \"").append(diagName).append("\"");
                if (!dict.isEmpty()) {
                    if (dict.get(0).get("snomed_code") != null) {
                        sb.append(" ;\n   med:snomedCode \"").append(dict.get(0).get("snomed_code")).append("\"");
                    }
                    if (dict.get(0).get("icd10") != null) {
                        sb.append(" ;\n   med:icd10Code \"").append(dict.get(0).get("icd10")).append("\"");
                    }
                }
                sb.append(" .\n").append(encUri).append(" med:hasDiagnosis ").append(diagUri).append(" .\n\n");
            }
        }

        // 药品医嘱（已执行）→ DrugOrder + Drug
        List<Map<String, Object>> drugOrders = his.queryForList(
                "SELECT * FROM medical_order WHERE inhos_no = ? AND order_type = '药品' AND order_status = '1'", inhosNo);
        for (Map<String, Object> o : drugOrders) {
            String orderUri = "med:DrugOrder_" + o.get("order_id");
            String drugUri = "med:Drug_" + o.get("item_code");
            String doctorUri = "med:Doctor_" + o.get("doctor");
            sb.append(orderUri).append(" a med:DrugOrder ;\n")
                    .append("   rdfs:label \"").append(o.get("item_name")).append(" 医嘱 ").append(o.get("order_id")).append("\" ;\n")
                    .append("   med:prescribesDrug ").append(drugUri).append(" ;\n")
                    .append("   med:prescribedBy ").append(doctorUri).append(" ;\n")
                    .append("   med:orderStatus \"已执行\"");
            // 剂量/频次（Shape 2 剂量上限的医嘱侧输入）
            if (o.get("single_dose") != null) {
                sb.append(" ;\n   med:singleDose \"").append(o.get("single_dose")).append("\"^^xsd:decimal");
            }
            if (o.get("frequency") != null) {
                sb.append(" ;\n   med:frequency \"").append(o.get("frequency")).append("\"");
            }
            sb.append(" .\n");
            // 药品知识（日最大剂量/含过敏原/儿童禁用/相互作用 → Shape 1/2/3/5 的药品侧输入）
            Map<String, Object> dk = drugDict.get(String.valueOf(o.get("item_code")));
            sb.append(drugUri).append(" a med:Drug ; rdfs:label \"").append(o.get("item_name")).append("\"");
            if (dk != null) {
                if (dk.get("max_daily_dose") != null) {
                    sb.append(" ;\n   med:maxDailyDose \"").append(dk.get("max_daily_dose")).append("\"^^xsd:decimal");
                }
                if (dk.get("allergen") != null) {
                    sb.append(" ;\n   med:containsAllergen med:Allergen_").append(dk.get("allergen"));
                }
                if ("Y".equals(String.valueOf(dk.get("child_forbidden")))) {
                    sb.append(" ;\n   med:childForbidden true");
                }
                if (dk.get("interacts_with") != null) {
                    for (String other : String.valueOf(dk.get("interacts_with")).split(",")) {
                        sb.append(" ;\n   med:interactsWith med:Drug_").append(other.trim());
                    }
                }
            }
            sb.append(" .\n");
            sb.append(doctorUri).append(" a med:Doctor ; rdfs:label \"").append(o.get("doctor")).append("\" .\n");
            sb.append(encUri).append(" med:hasOrder ").append(orderUri).append(" .\n");
            // 处方（审核闭环 Shape 6：处方必须有开立医生，且经药师审核通过方可发药）
            List<Map<String, Object>> passed = pharmacy.queryForList(
                    "SELECT pharmacist FROM presc_review WHERE order_id = ? AND review_result = '通过'", o.get("order_id"));
            sb.append("med:Prescription_").append(o.get("order_id")).append(" a med:Prescription ;\n")
                    .append("   rdfs:label \"处方 ").append(o.get("order_id")).append("\" ;\n")
                    .append("   med:prescribedBy ").append(doctorUri);
            if (!passed.isEmpty()) {
                sb.append(" ;\n   med:reviewedBy med:Pharmacist_").append(passed.get(0).get("pharmacist"));
            }
            sb.append(" .\n");
            if (!passed.isEmpty()) {
                sb.append("med:Pharmacist_").append(passed.get(0).get("pharmacist"))
                        .append(" a med:Pharmacist ; rdfs:label \"").append(passed.get(0).get("pharmacist")).append("\" .\n");
            }
            // 发药记录（药房）
            List<Map<String, Object>> dispenses = pharmacy.queryForList(
                    "SELECT * FROM dispense_record WHERE order_id = ? AND status = '1'", o.get("order_id"));
            for (Map<String, Object> d : dispenses) {
                sb.append("med:Dispense_").append(d.get("dispense_id")).append(" a med:ClinicalAct ;\n")
                        .append("   rdfs:label \"发药 ").append(d.get("dispense_id")).append("\" ;\n")
                        .append("   rdfs:comment \"药师 ").append(d.get("pharmacist")).append("\" .\n\n");
            }
        }

        // 检验结果（异常标记是危急值/质控 Shape 的输入）
        List<Map<String, Object>> reports = lis.queryForList(
                "SELECT r.*, a.item_name FROM lab_report r JOIN lab_apply a ON r.apply_id = a.apply_id WHERE r.patient_no = ?",
                inhosNo);
        for (Map<String, Object> r : reports) {
            String labUri = "med:LabResult_" + r.get("report_id");
            sb.append(labUri).append(" a med:LabResult ;\n")
                    .append("   rdfs:label \"").append(r.get("item_name")).append("报告 ").append(r.get("report_id")).append("\" ;\n")
                    .append("   med:isAbnormal ").append("A".equals(String.valueOf(r.get("result_status"))) ? "true" : "false")
                    .append(" .\n");
            sb.append(encUri).append(" med:hasOrder med:LabOrder_").append(r.get("apply_id")).append(" .\n");
            sb.append("med:LabOrder_").append(r.get("apply_id")).append(" a med:LabOrder ; rdfs:label \"")
                    .append(r.get("item_name")).append("\" .\n\n");
        }
        return sb.toString();
    }

    /** 姓名脱敏：张*三（两字名 张*；单字/空 → *）——统一实现见 common.Masking */
    static String maskName(String name) {
        return com.bemodel.common.Masking.maskName(name);
    }
}
