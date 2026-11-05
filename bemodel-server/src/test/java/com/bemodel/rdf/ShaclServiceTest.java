package com.bemodel.rdf;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SHACL 真校验单测（P0③）：不依赖数据库，直接以手工构造的患者 ABox Turtle
 * 驱动 clinical-shapes.ttl 六 Shape（Jena SHACL 引擎真跑 SPARQL 约束）。
 * 与 RdfServiceTest（ABox 装配）互补：这里证明的是"校验器本身真的会拦"。
 */
class ShaclServiceTest {

    private static final String PREFIX =
            "@prefix med:  <http://bemodel.com/ontology/med#> .\n" +
            "@prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .\n\n";

    /** 合规样本：女 45 岁，诊断 I10，处方经药师审核，剂量 10mg tid vs 日上限 500 */
    private static final String CONFORMING =
            "med:Patient_T1 a med:Patient ;\n" +
            "    med:sex \"女\" ;\n" +
            "    med:age \"45\"^^xsd:integer ;\n" +
            "    med:hasEncounter med:Enc_T1 .\n\n" +
            "med:Enc_T1 a med:InpEncounter , med:Encounter ;\n" +
            "    med:hasOrder med:Ord_T1 ;\n" +
            "    med:hasDiagnosis med:Diag_T1 .\n\n" +
            "med:Ord_T1 a med:DrugOrder ;\n" +
            "    med:prescribesDrug med:Drug_T1 ;\n" +
            "    med:singleDose \"10\"^^xsd:decimal ;\n" +
            "    med:frequency \"tid\" .\n\n" +
            "med:Drug_T1 a med:Drug ;\n" +
            "    med:maxDailyDose \"500\"^^xsd:decimal ;\n" +
            "    med:childForbidden false .\n\n" +
            "med:Diag_T1 a med:Diagnosis ;\n" +
            "    med:icd10Code \"I10\" .\n\n" +
            "med:Prc_T1 a med:Prescription ;\n" +
            "    med:prescribedBy \"Dr_A\" ;\n" +
            "    med:reviewedBy \"Ph_B\" .\n";

    private final ShaclService service = new ShaclService(null);

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> violationsOf(String abox) {
        Map<String, Object> result = service.validateTurtle(PREFIX + abox, "T1");
        assertEquals("T1", result.get("patientId"));
        return (List<Map<String, Object>>) result.get("violations");
    }

    @Test
    void 合规样本全部六Shape通过() {
        Map<String, Object> result = service.validateTurtle(PREFIX + CONFORMING, "T1");
        assertTrue((Boolean) result.get("conforms"), "合规样本应 conforms");
        assertEquals(0, result.get("violationCount"));
    }

    @Test
    void 剂量超限被拦截_Shape2() {
        // 10mg × tid(×3) = 30/日 → 上限改为 20 触发超限
        String abox = CONFORMING.replace("med:maxDailyDose \"500\"^^xsd:decimal",
                "med:maxDailyDose \"20\"^^xsd:decimal");
        List<Map<String, Object>> violations = violationsOf(abox);
        assertTrue(violations.stream().anyMatch(v -> v.get("message").toString().contains("日最大剂量")),
                "实际: " + violations);
    }

    @Test
    void 儿童禁用被拦截_Shape3() {
        String abox = CONFORMING
                .replace("med:age \"45\"^^xsd:integer", "med:age \"5\"^^xsd:integer")
                .replace("med:childForbidden false", "med:childForbidden true");
        List<Map<String, Object>> violations = violationsOf(abox);
        assertTrue(violations.stream().anyMatch(v -> v.get("message").toString().contains("儿童禁用")),
                "实际: " + violations);
    }

    @Test
    void 男性妊娠诊断被拦截_Shape4() {
        String abox = CONFORMING
                .replace("med:sex \"女\"", "med:sex \"男\"")
                .replace("med:icd10Code \"I10\"", "med:icd10Code \"O82\"");
        List<Map<String, Object>> violations = violationsOf(abox);
        assertTrue(violations.stream().anyMatch(v -> v.get("message").toString().contains("妊娠/妇科")),
                "实际: " + violations);
    }

    @Test
    void 过敏禁忌被拦截_Shape1() {
        String abox = CONFORMING +
                "\nmed:Patient_T1 med:hasAllergyTo med:Allergen_PCN .\n" +
                "med:Drug_T1 med:containsAllergen med:Allergen_PCN .\n";
        List<Map<String, Object>> violations = violationsOf(abox);
        assertTrue(violations.stream().anyMatch(v -> v.get("message").toString().contains("过敏禁忌")),
                "实际: " + violations);
    }

    @Test
    void 处方缺药师审核被拦截_Shape6() {
        // reviewedBy 不满足 minCount 1 → 处方完整性违约
        String abox = CONFORMING.replace("    med:reviewedBy \"Ph_B\" .\n", "");
        List<Map<String, Object>> violations = violationsOf(abox);
        assertTrue(violations.stream().anyMatch(v -> v.get("message").toString().contains("药师审核")),
                "实际: " + violations);
    }

    @Test
    void 违约条目四元组完整_focusNode_message_severity() {
        String abox = CONFORMING.replace("med:sex \"女\"", "med:sex \"男\"")
                .replace("med:icd10Code \"I10\"", "med:icd10Code \"O82\"");
        Map<String, Object> result = service.validateTurtle(PREFIX + abox, "T1");
        assertTrue(result.containsKey("shapes"));
        assertTrue(result.containsKey("engine"));
        for (Map<String, Object> v : violationsOf(abox)) {
            assertTrue(v.containsKey("focusNode"));
            assertTrue(v.containsKey("path"));
            assertTrue(v.containsKey("message"));
            assertEquals("Violation", v.get("severity"));
        }
    }
}
