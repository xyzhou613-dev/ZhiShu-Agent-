package com.bemodel.lab;

import com.bemodel.common.BizException;
import com.bemodel.common.CryptoService;
import com.bemodel.datasource.entity.Datasource;
import com.bemodel.datasource.service.DatasourceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 对比实验室沙箱(Plan 1):在平台同实例上维护独立沙箱库,供 B 组(AI+裸SQL)任意读写,
 * 与六条演示产品线库物理隔离——B 组写操作再野也只落在沙箱里,演示页(/flow /clinical /value)零污染。
 *
 * 克隆方式:逐表 CREATE TABLE LIKE + INSERT SELECT(评审勘误:MySQL 无 CREATE DATABASE LIKE 语法);
 * 标识符全部来自代码内配置,反引号引用,不拼接任何用户输入。
 * DS_LAB 数据源行在此登记(密码 AES 加密,与 V3 明文种子走 SecretMigrator 原地加密同路径);
 * 登记后 DatasourceService.jdbc("DS_LAB") 即可用,治理探针 expr 的 ds 字段也可直接指向沙箱(配置级零代码)。
 */
@Slf4j
@Service
public class LabSandboxService {

    public static final String DS_LAB = "DS_LAB";

    private final JdbcTemplate platform;
    private final DatasourceService datasourceService;
    private final CryptoService cryptoService;
    private final LabProps props;
    private final String platformSchema;
    private final String platformHost;
    private final int platformPort;
    private final String platformUsername;
    private final String platformPassword;

    public LabSandboxService(DataSource dataSource, DatasourceService datasourceService,
                             CryptoService cryptoService, LabProps props,
                             @Value("${spring.datasource.url}") String url,
                             @Value("${spring.datasource.username}") String username,
                             @Value("${spring.datasource.password}") String password) {
        this.platform = new JdbcTemplate(dataSource);
        this.datasourceService = datasourceService;
        this.cryptoService = cryptoService;
        this.props = props;
        Matcher m = Pattern.compile("jdbc:mysql://([^:/]+):(\\d+)/([^?&]+)").matcher(url);
        if (!m.find()) {
            throw new IllegalStateException("无法从 spring.datasource.url 解析 host/port/schema: " + url);
        }
        this.platformHost = m.group(1);
        this.platformPort = Integer.parseInt(m.group(2));
        this.platformSchema = m.group(3);
        this.platformUsername = username;
        this.platformPassword = password;
    }

    /** 幂等确保:守卫库名 → 建库(如缺) → 逐表补克隆 → 登记 DS_LAB。返回每表行数 */
    public synchronized Map<String, Object> ensureCloned() {
        guardDbName();
        ensureDatabase();
        List<Map<String, Object>> tables = new ArrayList<>();
        for (String spec : props.getCloneTables()) {
            String[] parts = spec.split("\\.");
            if (parts.length != 2) {
                throw new BizException("clone-tables 配置格式应为 源库.表名: " + spec);
            }
            tables.add(cloneIfMissing(parts[0], parts[1]));
        }
        registerDatasourceRow();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("db", props.getDbName());
        result.put("tables", tables);
        return result;
    }

    /** 一键重置:DROP 沙箱库再全量重克隆。演示彩排/实验转场用,幂等 */
    public synchronized Map<String, Object> reset() {
        guardDbName();
        platform.execute("DROP DATABASE IF EXISTS `" + props.getDbName() + "`");
        return ensureCloned();
    }

    private void ensureDatabase() {
        Long cnt = platform.queryForObject(
                "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE schema_name = ?",
                Long.class, props.getDbName());
        if (cnt == null || cnt == 0) {
            platform.execute("CREATE DATABASE `" + props.getDbName() + "` DEFAULT CHARACTER SET utf8mb4");
            log.info("LAB 沙箱库已创建: {}", props.getDbName());
        }
    }

    private Map<String, Object> cloneIfMissing(String srcDb, String table) {
        Long exists = platform.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES WHERE table_schema = ? AND table_name = ?",
                Long.class, props.getDbName(), table);
        if (exists == null || exists == 0) {
            platform.execute("CREATE TABLE `" + props.getDbName() + "`.`" + table + "` LIKE `" + srcDb + "`.`" + table + "`");
            platform.execute("INSERT INTO `" + props.getDbName() + "`.`" + table + "` SELECT * FROM `" + srcDb + "`.`" + table + "`");
        }
        Long rows = platform.queryForObject(
                "SELECT COUNT(*) FROM `" + props.getDbName() + "`.`" + table + "`", Long.class);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("table", srcDb + "." + table);
        row.put("rows", rows == null ? 0L : rows);
        return row;
    }

    /** 库名守卫:折叠大小写后校验——平台库/demo_* /系统库/非法字符一律拒绝(DROP 只许落在沙箱库上) */
    private void guardDbName() {
        String name = props.getDbName();
        String folded = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (folded.isBlank()
                || !folded.matches("[a-z0-9_]+")
                || folded.equals(platformSchema.toLowerCase(Locale.ROOT))
                || folded.startsWith("demo_")
                || folded.equals("mysql") || folded.equals("sys")
                || folded.equals("information_schema") || folded.equals("performance_schema")) {
            throw new BizException("LAB 沙箱库名非法(仅限字母数字下划线,不得为平台库/demo_*/系统库): " + name);
        }
    }

    /** 登记/校正 DS_LAB 数据源行:凭证取自平台连接(spring.datasource.*),密码 AES 加密落库 */
    private void registerDatasourceRow() {
        Datasource existing = datasourceService.getByCode(DS_LAB);
        if (existing == null) {
            Datasource ds = new Datasource();
            ds.setDsCode(DS_LAB);
            ds.setDsName("AI对比实验室沙箱");
            ds.setProductName("AI对比实验室");
            ds.setDbType("MYSQL");
            ds.setHost(platformHost);
            ds.setPort(platformPort);
            ds.setDbName(props.getDbName());
            ds.setUsername(platformUsername);
            ds.setPassword(cryptoService.encrypt(platformPassword));
            datasourceService.save(ds);
            log.info("LAB 沙箱数据源已登记: {} -> {}", DS_LAB, props.getDbName());
        } else if (!props.getDbName().equals(existing.getDbName())) {
            existing.setDbName(props.getDbName());
            datasourceService.updateById(existing);
        }
    }
}
