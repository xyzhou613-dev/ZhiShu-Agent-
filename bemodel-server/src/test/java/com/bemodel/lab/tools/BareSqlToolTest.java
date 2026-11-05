package com.bemodel.lab.tools;

import com.bemodel.lab.LabSandboxService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** B 臂护栏:越出沙箱一律拒绝;沙箱内读写放行;结果集截断 */
@SpringBootTest
class BareSqlToolTest {

    @Autowired
    private BareSqlTool bareSqlTool;
    @Autowired
    private LabSandboxService sandboxService;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void ensureSandbox() {
        sandboxService.ensureCloned();
    }

    private Map<String, Object> exec(String sql) {
        JsonNode args = om.valueToTree(Map.of("sql", sql));
        return bareSqlTool.execute(args);
    }

    @Test
    void crossDbRefused() {
        Map<String, Object> out = exec("SELECT COUNT(*) AS c FROM bemodel_platform.bm_concept");
        assertTrue(String.valueOf(out.get("error")).contains("跨库"));
    }

    @Test
    void productDbPrefixRefused() {
        Map<String, Object> out = exec("SELECT COUNT(*) AS c FROM demo_his.inpatient");
        assertTrue(String.valueOf(out.get("error")).contains("跨库"));
        Map<String, Object> out2 = exec("SELECT * FROM mysql.user LIMIT 1");
        assertTrue(String.valueOf(out2.get("error")).contains("跨库"));
        // 反引号限定名同样必须被拦截(评审 Important:原正则漏掉反引号形态)
        Map<String, Object> out3 = exec("SELECT * FROM `mysql`.`user` LIMIT 1");
        assertTrue(String.valueOf(out3.get("error")).contains("跨库"));
        Map<String, Object> out4 = exec("SELECT COUNT(*) AS c FROM `bemodel_platform`.`bm_concept`");
        assertTrue(String.valueOf(out4.get("error")).contains("跨库"));
    }

    @Test
    void useAndMultiStatementRefused() {
        assertTrue(exec("USE bemodel_lab").containsKey("error"));
        assertTrue(exec("SELECT 1; SELECT 2").containsKey("error"));
        assertTrue(exec("DROP DATABASE bemodel_lab").containsKey("error"));
    }

    @Test
    void selectTruncates() {
        // drug_dict 12 行自连接 144 行,超过 20 行上限
        Map<String, Object> out = exec("SELECT d.drug_code FROM drug_dict d CROSS JOIN drug_dict d2");
        assertEquals(true, out.get("truncated"));
        assertEquals(20, ((List<?>) out.get("rows")).size());
        assertEquals(144L, ((Number) out.get("rowTotal")).longValue());
    }

    @Test
    void sandboxWriteAllowed() {
        // 同值自更新:证明写通道在,又不改数据
        Map<String, Object> out = exec("UPDATE drug_stock SET quantity = quantity WHERE drug_code = 'D006'");
        assertTrue(out.containsKey("affected"));
        assertFalse(out.containsKey("error"));
    }

    @Test
    void badSqlReturnsErrorNotThrow() {
        Map<String, Object> out = exec("SELECT no_such_column FROM drug_dict");
        assertTrue(out.containsKey("error"));
    }
}
