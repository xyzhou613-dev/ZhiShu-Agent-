package com.bemodel.lab;

import com.bemodel.common.BizException;
import com.bemodel.common.CryptoService;
import com.bemodel.datasource.entity.Datasource;
import com.bemodel.datasource.service.DatasourceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LAB 沙箱(Plan 1)锚点测试:克隆幂等/重置幂等/DS_LAB 登记且密码加密/库名守卫。
 * 直连本机演示库;只写 bemodel_lab,绝不触碰 demo_* 数据。
 */
@SpringBootTest
class LabSandboxServiceTest {

    @Autowired
    private LabSandboxService sandbox;
    @Autowired
    private DatasourceService datasourceService;
    @Autowired
    private LabProps props;

    @Test
    @SuppressWarnings("unchecked")
    void ensureCloned_idempotent_and_registersDsLab() {
        Map<String, Object> first = sandbox.ensureCloned();
        assertEquals(props.getDbName(), first.get("db"));
        assertEquals(props.getCloneTables().size(), ((List<?>) first.get("tables")).size());
        // 幂等:二次调用全表已在,不重复克隆,返回逐字段一致
        assertEquals(first, sandbox.ensureCloned());
        // DS_LAB 已登记、指向沙箱库、密码已 AES 加密(明文不落库)
        Datasource ds = datasourceService.getByCode(LabSandboxService.DS_LAB);
        assertNotNull(ds);
        assertEquals(props.getDbName(), ds.getDbName());
        assertTrue(ds.getPassword().startsWith(CryptoService.PREFIX), "DS_LAB 密码应加密落库");
    }

    @Test
    @SuppressWarnings("unchecked")
    void reset_rebuilds_all_tables_with_same_rows() {
        List<Map<String, Object>> before = (List<Map<String, Object>>) sandbox.ensureCloned().get("tables");
        List<Map<String, Object>> after = (List<Map<String, Object>>) sandbox.reset().get("tables");
        assertEquals(before, after);
    }

    @Test
    void guard_rejects_dangerous_db_name() {
        String origin = props.getDbName();
        props.setDbName("demo_his");
        try {
            assertThrows(BizException.class, () -> sandbox.reset());
            assertThrows(BizException.class, () -> sandbox.ensureCloned());
        } finally {
            props.setDbName(origin);
        }
    }

    @Test
    void guard_rejects_case_variants_and_illegal_chars() {
        String origin = props.getDbName();
        try {
            props.setDbName("Demo_HIS");
            assertThrows(BizException.class, () -> sandbox.reset());
            props.setDbName("bemodel`lab");
            assertThrows(BizException.class, () -> sandbox.reset());
        } finally {
            props.setDbName(origin);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void 克隆清单覆盖过敏史与科室() {
        Map<String, Object> out = sandbox.ensureCloned();
        List<Map<String, Object>> tables = (List<Map<String, Object>>) out.get("tables");
        List<String> names = tables.stream().map(t -> String.valueOf(t.get("table"))).toList();
        assertTrue(names.contains("demo_emr.patient_allergy"), "E1 需要过敏史表");
        assertTrue(names.contains("demo_his.dept"), "E4 需要科室表");
        // 种子锚点:陈芳的头孢过敏史必须随克隆进沙箱
        Long allergyRows = datasourceService.jdbc(LabSandboxService.DS_LAB).queryForObject(
                "SELECT COUNT(*) FROM patient_allergy WHERE inhos_no = 'ZY20260805006'", Long.class);
        assertTrue(allergyRows != null && allergyRows >= 1, "陈芳过敏史种子缺失");
    }
}
