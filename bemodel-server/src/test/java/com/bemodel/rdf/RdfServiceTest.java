package com.bemodel.rdf;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ABox 导出验证：张建国（冠心病诊断 + 已执行药品医嘱）应导出完整的处方审核原型三元组。
 */
@SpringBootTest
class RdfServiceTest {

    @Autowired
    private RdfService rdfService;

    @Test
    void exportPatientShouldProduceCompleteABox() {
        String ttl = rdfService.exportPatient("ZY20260815001");

        assertTrue(ttl.contains("@prefix med:"), "应含命名空间前缀");
        assertTrue(ttl.contains("med:Patient_ZY20260815001 a med:Patient"), "应含患者实例");
        assertTrue(ttl.contains("med:sex \"男\""), "应含性别数据属性");
        assertTrue(ttl.contains("a med:InpEncounter"), "应含住院就诊实例");
        assertTrue(ttl.contains("a med:DrugOrder"), "应含药品医嘱实例");
        assertTrue(ttl.contains("med:prescribesDrug med:Drug_"), "应含开立药品关系");
        assertTrue(ttl.contains("med:hasDiagnosis"), "应含确诊关系（EMR病案诊断）");
        assertTrue(ttl.contains("med:icd10Code"), "诊断应携带ICD-10标准编码");
        assertTrue(ttl.contains("a med:LabResult"), "应含检验结果实例");
        assertTrue(ttl.contains("med:isAbnormal"), "检验结果应含异常标记（SHACL输入）");

        // Turtle 结构基本校验：每条语句以 . 结尾
        long statements = ttl.lines().filter(l -> l.trim().endsWith(".")).count();
        assertTrue(statements > 10, "应有足够三元组语句: " + statements);
    }

    @Test
    void exportPatientShouldCarryPrescriptionReviewInputs() {
        // 陈芳（ZY20260805006）：头孢过敏 + 循环医嘱含 D006 头孢克肟 → Shape 1 过敏禁忌的完整输入
        String cf = rdfService.exportPatient("ZY20260805006");
        assertTrue(cf.contains("med:hasAllergyTo med:Allergen_头孢"), "过敏史应投影为 hasAllergyTo 三元组");
        assertTrue(cf.contains("med:Allergen_头孢 a med:Allergen"), "应含过敏原实例");
        assertTrue(cf.contains("med:Drug_D006"), "陈芳医嘱应含 D006 头孢克肟");
        assertTrue(cf.contains("med:containsAllergen med:Allergen_头孢"), "D006 应标注含头孢过敏原");

        // 冯雪（ZY20260728012）：布洛芬 1.0g tid 超日最大 2.4g → Shape 2 剂量上限的完整输入
        String fx = rdfService.exportPatient("ZY20260728012");
        assertTrue(fx.contains("med:singleDose \"1.00\"^^xsd:decimal")
                || fx.contains("med:singleDose \"1.0\"^^xsd:decimal"), "超量医嘱应携带 singleDose=1.0");
        assertTrue(fx.contains("med:frequency \"tid\""), "超量医嘱应携带 frequency=tid");
        assertTrue(fx.contains("med:maxDailyDose \"2.40\"^^xsd:decimal")
                || fx.contains("med:maxDailyDose \"2.4\"^^xsd:decimal"), "D002 应携带 maxDailyDose=2.4");

        // 张建国（对照组）：剂量三元组齐备但无过敏史、日剂量均在限内 → 三 Shape 均不应命中
        String zjg = rdfService.exportPatient("ZY20260815001");
        assertTrue(zjg.contains("med:singleDose"), "对照组医嘱也应携带剂量");
        assertFalse(zjg.contains("med:hasAllergyTo"), "对照组无过敏史");
    }

    @Test
    void exportPatientShouldCarryShape356Inputs() {
        // 韩小梅（8岁，儿童禁用案例）：布洛芬缓释胶囊 childForbidden=true → Shape 3 输入
        String hxm = rdfService.exportPatient("ZY20260903041");
        assertTrue(hxm.contains("med:age \"8\"^^xsd:integer"), "儿童患者应携带年龄");
        assertTrue(hxm.contains("med:Drug_D002"), "应有布洛芬医嘱");
        assertTrue(hxm.contains("med:childForbidden true"), "D002 应标注儿童禁用");

        // 郑国庆（相互作用案例）：华法林 ↔ 布洛芬同就诊联用 → Shape 5 输入
        String zgq = rdfService.exportPatient("ZY20260802011");
        assertTrue(zgq.contains("med:Drug_D012"), "应有华法林医嘱");
        assertTrue(zgq.contains("med:interactsWith"), "应携带相互作用三元组");

        // 冯雪（审核驳回案例）：超量布洛芬医嘱被药师驳回 → 处方无 reviewedBy → Shape 6 命中
        String fx = rdfService.exportPatient("ZY20260728012");
        assertTrue(fx.contains("a med:Prescription"), "应有处方实例");
        // 驳回医嘱的处方节点不允许出现审核通过标记
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("med:Prescription_MO202607290073[\\s\\S]*?\\.", java.util.regex.Pattern.MULTILINE)
                .matcher(fx);
        assertTrue(m.find(), "应能找到驳回医嘱的处方节点");
        assertFalse(m.group().contains("med:reviewedBy"), "被驳回处方的处方节点不得有 reviewedBy（Shape 6 命中条件）");
        // 其余通过审核的处方有 reviewedBy
        assertTrue(fx.contains("med:reviewedBy med:Pharmacist_"), "通过审核的处方应有审核药师");
    }
}
