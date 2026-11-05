package com.bemodel.lab.tools;

import com.bemodel.datasource.service.DatasourceService;
import com.bemodel.lab.LabSandboxService;
import com.bemodel.lab.engine.LabTool;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * B 臂(AI+裸SQL)唯一工具:对沙箱库执行任意单条 SQL(含写)。
 * 护栏只在「越出沙箱」处:拒绝多语句 / USE / 建删库 / 一切跨库限定名(平台库、系统库、demo_ 前缀产品库);
 * 沙箱内写操作放行——B 臂应有能力而没有本体护栏,越权与否由模型自觉,后果由审计探针(LabAuditService)揭穿。
 * 结果集截断(20 行×120 字符)后才进轨迹:B 臂步进会重复携带历史结果,不截断则线性膨胀。
 */
@Component
@RequiredArgsConstructor
public class BareSqlTool implements LabTool {

    /** 拒绝一切「库前缀限定」引用(裸名或反引号限定名均可):沙箱连接已绑定 bemodel_lab,裸表名即沙箱表 */
    private static final Pattern CROSS_DB = Pattern.compile(
            "(?i)`?\\b(bemodel_platform|mysql|sys|information_schema|performance_schema|demo_[a-z_]+)\\b`?\\s*\\.");
    private static final Pattern FORBIDDEN = Pattern.compile(
            "(?i)\\b(use|grant|revoke)\\b|(?i)\\b(create|drop)\\s+(database|schema)\\b");
    private static final int MAX_SQL_LENGTH = 4000;
    private static final int MAX_ROWS = 20;
    private static final int MAX_CELL_LENGTH = 120;

    private final DatasourceService datasourceService;

    @Override
    public String name() {
        return "execute_sql";
    }

    @Override
    public String description() {
        return "对沙箱库 bemodel_lab 执行一条任意 SQL(SELECT/UPDATE/INSERT/DELETE 均可),读返回行数据,写返回受影响行数";
    }

    @Override
    public String argsSchema() {
        return "{\"sql\":\"字符串,单条 SQL,不要分号结尾\"}";
    }

    @Override
    public Map<String, Object> execute(JsonNode args) {
        String sql = args.path("sql").asText("").trim();
        Map<String, Object> out = new LinkedHashMap<>();
        if (sql.isEmpty() || sql.length() > MAX_SQL_LENGTH) {
            out.put("error", "SQL 为空或超过 " + MAX_SQL_LENGTH + " 字符");
            return out;
        }
        String stmt = sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql;
        if (stmt.contains(";")) {
            out.put("error", "只允许单条语句(SQL 文本中出现多个分号)");
            return out;
        }
        if (FORBIDDEN.matcher(stmt).find()) {
            out.put("error", "USE/GRANT/建库删库不在沙箱允许范围");
            return out;
        }
        if (CROSS_DB.matcher(stmt).find()) {
            out.put("error", "只能访问沙箱库 bemodel_lab(不允许跨库引用平台库/系统库/产品库)");
            return out;
        }
        JdbcTemplate lab = datasourceService.jdbc(LabSandboxService.DS_LAB);
        try {
            String lower = stmt.toLowerCase();
            if (lower.startsWith("select") || lower.startsWith("show") || lower.startsWith("desc")) {
                List<Map<String, Object>> rows = lab.queryForList(stmt);
                out.put("rowTotal", (long) rows.size());
                List<Map<String, Object>> shown = new ArrayList<>();
                for (Map<String, Object> row : rows.subList(0, Math.min(rows.size(), MAX_ROWS))) {
                    Map<String, Object> cell = new LinkedHashMap<>();
                    row.forEach((k, v) -> cell.put(k, clip(v)));
                    shown.add(cell);
                }
                out.put("rows", shown);
                if (rows.size() > MAX_ROWS) {
                    out.put("truncated", true);
                    out.put("note", "仅返回前 " + MAX_ROWS + " 行");
                }
            } else {
                out.put("affected", lab.update(stmt));
            }
        } catch (Exception e) {
            out.put("error", String.valueOf(e.getMessage()));
        }
        return out;
    }

    private String clip(Object v) {
        String s = String.valueOf(v);
        return s.length() > MAX_CELL_LENGTH ? s.substring(0, MAX_CELL_LENGTH) + "…" : s;
    }
}
