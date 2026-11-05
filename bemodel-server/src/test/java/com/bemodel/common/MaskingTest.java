package com.bemodel.common;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 患者身份脱敏（P0②）纯单元测试：无 Spring 上下文依赖 */
class MaskingTest {

    @Test
    void 姓名打码_保留姓与末字_两字张星_单字星() {
        assertEquals("张*丰", Masking.maskName("张三丰"));
        assertEquals("张*", Masking.maskName("张三"));
        assertEquals("*", Masking.maskName("张"));
        assertEquals("*", Masking.maskName(null));
        assertEquals("*", Masking.maskName(""));
        // 已打码值再打码保持稳定（幂等）
        assertEquals("张*丰", Masking.maskName("张*丰"));
    }

    @Test
    void 姓名类键识别_物理列与本体属性两种形态() {
        assertTrue(Masking.isNameKey("patient_name"));
        assertTrue(Masking.isNameKey("PATIENT"));
        assertTrue(Masking.isNameKey("pat_name"));
        assertTrue(Masking.isNameKey("姓名"));
        assertTrue(Masking.isNameKey("患者姓名"));
        assertFalse(Masking.isNameKey("inhos_no"));      // 业务键保留
        assertFalse(Masking.isNameKey("pat_card_no"));   // 业务键保留
        assertFalse(Masking.isNameKey("item_name"));
        assertFalse(Masking.isNameKey(null));
    }

    @Test
    void 行脱敏_只动姓名键_业务键与空值原样() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("inhos_no", "INH2024001");
        row.put("patient_name", "张三丰");
        row.put("amount", "120.50");

        Map<String, Object> masked = Masking.maskPatientRow(row);

        assertEquals("张*丰", masked.get("patient_name"));
        assertEquals("INH2024001", masked.get("inhos_no")); // 业务键保留
        assertEquals("120.50", masked.get("amount"));
        assertEquals(3, masked.size());
        // 原行不被修改（副本语义）
        assertEquals("张三丰", row.get("patient_name"));
    }

    @Test
    void 行脱敏_姓名为空值不产出星号噪声() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("patient_name", null);
        Map<String, Object> masked = Masking.maskPatientRow(row);
        assertNull(masked.get("patient_name"));
    }

    @Test
    void 行列表脱敏_逐行副本_行数不变() {
        List<Map<String, Object>> rows = List.of(
                Map.of("patient_name", "张三丰", "inhos_no", "A1"),
                Map.of("pat_name", "李四", "inhos_no", "A2"));
        List<Map<String, Object>> masked = Masking.maskPatientRows(rows);
        assertEquals(2, masked.size());
        assertEquals("张*丰", masked.get(0).get("patient_name"));
        assertEquals("李*", masked.get(1).get("pat_name"));
        assertEquals("A1", masked.get(0).get("inhos_no"));
    }
}
