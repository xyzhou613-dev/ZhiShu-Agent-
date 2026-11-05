package com.bemodel.cs;

import com.bemodel.common.BizException;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** validateSql 纯函数单测：只读/表列白名单/LIMIT 钳制/字面量防绕过（不依赖 LLM 与 Spring） */
class SemanticQaServiceTest {

    private static final Set<String> TABLES = Set.of("fee_detail", "medical_order", "inpatient");
    private static final Set<String> COLUMNS = Set.of(
            "fee_id", "order_id", "inhos_no", "item_name", "amount", "fee_status",
            "order_status", "order_type", "create_time", "patient_name", "sex", "dept_code");

    @Test
    void validSelectShouldPassAndAppendLimit() {
        String sql = SemanticQaService.validateSql(
                "SELECT item_name, amount FROM fee_detail WHERE fee_status = '1'", TABLES, COLUMNS);
        assertTrue(sql.endsWith("LIMIT 100"), "无 LIMIT 应自动追加: " + sql);
    }

    @Test
    void shouldRejectNonSelectAndWrites() {
        assertThrows(BizException.class, () ->
                SemanticQaService.validateSql("DELETE FROM fee_detail", TABLES, COLUMNS));
        assertThrows(BizException.class, () ->
                SemanticQaService.validateSql("UPDATE fee_detail SET amount = 0", TABLES, COLUMNS));
        assertThrows(BizException.class, () ->
                SemanticQaService.validateSql("SELECT amount FROM fee_detail; DROP TABLE fee_detail", TABLES, COLUMNS),
                "多语句必须拒绝");
        // SELECT ... INTO 变体
        assertThrows(BizException.class, () ->
                SemanticQaService.validateSql("SELECT amount INTO OUTFILE '/tmp/x' FROM fee_detail", TABLES, COLUMNS));
    }

    @Test
    void shouldRejectTableOutsideWhitelist() {
        BizException e = assertThrows(BizException.class, () ->
                SemanticQaService.validateSql("SELECT * FROM user_passwords", TABLES, COLUMNS));
        assertTrue(e.getMessage().contains("表白名单") || e.getMessage().contains("白名单"));
    }

    @Test
    void shouldRejectColumnOutsideWhitelist() {
        // SELECT 首位直接引用白名单外列
        assertThrows(BizException.class, () ->
                SemanticQaService.validateSql("SELECT secret_col FROM fee_detail", TABLES, COLUMNS));
        // 逗号后新表达式首位同样校验
        assertThrows(BizException.class, () ->
                SemanticQaService.validateSql("SELECT item_name, secret_col FROM fee_detail", TABLES, COLUMNS));
        // WHERE 中的白名单外列
        assertThrows(BizException.class, () ->
                SemanticQaService.validateSql("SELECT item_name FROM fee_detail WHERE secret_col = '1'", TABLES, COLUMNS));
        // 点号限定列同样校验
        assertThrows(BizException.class, () ->
                SemanticQaService.validateSql("SELECT f.secret_col FROM fee_detail f", TABLES, COLUMNS));
    }

    @Test
    void shouldClampAndRespectLimit() {
        String clamped = SemanticQaService.validateSql(
                "SELECT item_name FROM fee_detail LIMIT 500", TABLES, COLUMNS);
        assertTrue(clamped.contains("LIMIT 100"), "LIMIT 应钳到 100: " + clamped);
        String kept = SemanticQaService.validateSql(
                "SELECT item_name FROM fee_detail LIMIT 20", TABLES, COLUMNS);
        assertTrue(kept.contains("LIMIT 20"), "LIMIT ≤100 保持不变: " + kept);
    }

    @Test
    void stringLiteralShouldNotBypassChecks() {
        // 字面量里的分号与危险关键字不构成绕过；合法查询应通过
        String sql = SemanticQaService.validateSql(
                "SELECT item_name FROM fee_detail WHERE item_name = 'x; DROP TABLE fee_detail--'",
                TABLES, COLUMNS);
        assertTrue(sql.endsWith("LIMIT 100"));
        // 注释尾巴藏危险语句：去注释后是合法 SELECT
        String sql2 = SemanticQaService.validateSql(
                "SELECT item_name FROM fee_detail -- WHERE 1=1; DELETE FROM fee_detail", TABLES, COLUMNS);
        assertTrue(sql2.endsWith("LIMIT 100"));
    }

    @Test
    void keywordsInsideIdentifiersShouldNotFalsePositive() {
        // order_id/order_status 含 order、create_time 含 create：词边界保护不误伤
        String sql = SemanticQaService.validateSql(
                "SELECT o.order_id, o.create_time FROM medical_order o WHERE o.order_status = '2'",
                TABLES, COLUMNS);
        assertNotNull(sql);
    }

    @Test
    void aliasesAndAggregatesShouldPass() {
        String sql = SemanticQaService.validateSql(
                "SELECT fee_status, COUNT(*) cnt, SUM(amount) total FROM fee_detail "
                        + "GROUP BY fee_status ORDER BY total DESC",
                TABLES, COLUMNS);
        assertTrue(sql.contains("COUNT(*)"), "聚合与别名应通过: " + sql);
    }

    @Test
    void joinWithinSameDatasourceShouldPass() {
        String sql = SemanticQaService.validateSql(
                "SELECT f.item_name, f.amount FROM fee_detail f JOIN medical_order o ON f.order_id = o.order_id "
                        + "WHERE o.order_status = '2' AND f.fee_status = '1'",
                TABLES, COLUMNS);
        assertTrue(sql.endsWith("LIMIT 100"));
        // 反引号包裹的表名/列名
        String bt = SemanticQaService.validateSql(
                "SELECT `item_name` FROM `fee_detail` WHERE `fee_status` = '1'", TABLES, COLUMNS);
        assertNotNull(bt);
    }
}
