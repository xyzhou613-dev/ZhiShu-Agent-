package com.bemodel.simulation.action;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bemodel.common.BizException;
import com.bemodel.datasource.entity.Mapping;
import com.bemodel.datasource.mapper.MappingMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * STOCK_CUT 施加器(spec §5):目标表/列由 DRUG_STOCK 的 ACTIVE 映射解析,不硬编码表名——
 * 施加与传导同一套本体定位机制。只落 DS_LAB 沙箱,施加前记录前值。
 */
@Component
public class StockCutAction {

    /** 标识符白名单:映射种子虽可信,仍按 LabSandboxService 同款纪律校验后反引号引用 */
    private static final Pattern SAFE_IDENT = Pattern.compile("[a-zA-Z0-9_]+");

    private final MappingMapper mappingMapper;

    public StockCutAction(MappingMapper mappingMapper) {
        this.mappingMapper = mappingMapper;
    }

    public record Applied(String table, String column, String keyColumn, String keyValue,
                          int before, int after) {}

    public Applied apply(JdbcTemplate lab, String drugCode, int quantity) {
        Mapping qty = mappingMapper.selectOne(new LambdaQueryWrapper<Mapping>()
                .eq(Mapping::getConceptCode, "DRUG_STOCK").eq(Mapping::getAttrCode, "quantity")
                .eq(Mapping::getStatus, "ACTIVE").last("LIMIT 1"));
        Mapping key = mappingMapper.selectOne(new LambdaQueryWrapper<Mapping>()
                .eq(Mapping::getConceptCode, "DRUG_STOCK").eq(Mapping::getAttrCode, "drug_code")
                .eq(Mapping::getStatus, "ACTIVE").last("LIMIT 1"));
        if (qty == null || key == null || !qty.getTableName().equals(key.getTableName())) {
            throw new BizException("药品库存的数量/编码映射缺失或不同表,场景不可用(不造映射)");
        }
        String table = guard(qty.getTableName());
        String column = guard(qty.getColumnName());
        String keyColumn = guard(key.getColumnName());
        Integer before;
        try {
            before = lab.queryForObject(
                    "SELECT `" + column + "` FROM `" + table + "` WHERE `" + keyColumn + "` = ?",
                    Integer.class, drugCode);
        } catch (EmptyResultDataAccessException noRow) {
            before = null; // 单行 queryForObject 查无行是抛异常而非回 null,归一后交给下方如实报错
        }
        if (before == null) {
            throw new BizException("演示数据里查无这个药品的库存: " + drugCode);
        }
        lab.update("UPDATE `" + table + "` SET `" + column + "` = ? WHERE `" + keyColumn + "` = ?",
                quantity, drugCode);
        return new Applied(table, column, keyColumn, drugCode, before, quantity);
    }

    private String guard(String ident) {
        if (ident == null || !SAFE_IDENT.matcher(ident).matches()) {
            throw new BizException("映射含非法标识符: " + ident);
        }
        return ident;
    }
}
