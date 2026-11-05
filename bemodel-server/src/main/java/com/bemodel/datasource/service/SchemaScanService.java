package com.bemodel.datasource.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bemodel.common.BizException;
import com.bemodel.datasource.entity.Datasource;
import com.bemodel.datasource.entity.PhysicalColumn;
import com.bemodel.datasource.entity.PhysicalTable;
import com.bemodel.datasource.mapper.PhysicalColumnMapper;
import com.bemodel.datasource.mapper.PhysicalTableMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 通过 information_schema 扫描产品库物理结构，落库为快照。
 * 平台不直连业务库的 ORM，只读元数据，安全且符合"物理自治、逻辑统一"原则。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchemaScanService extends ServiceImpl<PhysicalTableMapper, PhysicalTable> {

    private final DatasourceService datasourceService;
    private final PhysicalTableMapper tableMapper;
    private final PhysicalColumnMapper columnMapper;

    @Transactional
    public int scan(String dsCode) {
        Datasource ds = datasourceService.getByCode(dsCode);
        if (ds == null) {
            throw new BizException("数据源不存在: " + dsCode);
        }
        var jdbc = datasourceService.jdbc(dsCode);

        List<Map<String, Object>> tables = jdbc.queryForList(
                "SELECT TABLE_NAME, TABLE_COMMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?", ds.getDbName());
        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, COLUMN_COMMENT, COLUMN_KEY, ORDINAL_POSITION " +
                        "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? ORDER BY TABLE_NAME, ORDINAL_POSITION", ds.getDbName());

        tableMapper.delete(new LambdaQueryWrapper<PhysicalTable>().eq(PhysicalTable::getDsCode, dsCode));
        columnMapper.delete(new LambdaQueryWrapper<PhysicalColumn>().eq(PhysicalColumn::getDsCode, dsCode));

        LocalDateTime now = LocalDateTime.now();
        for (Map<String, Object> t : tables) {
            PhysicalTable pt = new PhysicalTable();
            pt.setDsCode(dsCode);
            pt.setTableName(String.valueOf(t.get("TABLE_NAME")));
            pt.setTableComment(String.valueOf(t.getOrDefault("TABLE_COMMENT", "")));
            pt.setScannedAt(now);
            tableMapper.insert(pt);
        }
        for (Map<String, Object> c : columns) {
            PhysicalColumn pc = new PhysicalColumn();
            pc.setDsCode(dsCode);
            pc.setTableName(String.valueOf(c.get("TABLE_NAME")));
            pc.setColumnName(String.valueOf(c.get("COLUMN_NAME")));
            pc.setDataType(String.valueOf(c.get("DATA_TYPE")));
            pc.setColumnComment(String.valueOf(c.getOrDefault("COLUMN_COMMENT", "")));
            pc.setIsPk("PRI".equals(String.valueOf(c.get("COLUMN_KEY"))) ? 1 : 0);
            pc.setOrdinalPosition(((Number) c.get("ORDINAL_POSITION")).intValue());
            columnMapper.insert(pc);
        }
        log.info("数据源 {} 扫描完成：{} 张表 / {} 个字段", dsCode, tables.size(), columns.size());
        return tables.size();
    }

    public List<PhysicalTable> tables(String dsCode) {
        return tableMapper.selectList(new LambdaQueryWrapper<PhysicalTable>()
                .eq(PhysicalTable::getDsCode, dsCode).orderByAsc(PhysicalTable::getTableName));
    }

    public List<PhysicalColumn> columns(String dsCode, String tableName) {
        return columnMapper.selectList(new LambdaQueryWrapper<PhysicalColumn>()
                .eq(PhysicalColumn::getDsCode, dsCode)
                .eq(tableName != null && !tableName.isBlank(), PhysicalColumn::getTableName, tableName)
                .orderByAsc(PhysicalColumn::getOrdinalPosition));
    }
}
