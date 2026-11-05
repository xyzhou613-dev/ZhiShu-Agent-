package com.bemodel.datasource.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("bm_physical_column")
public class PhysicalColumn {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String dsCode;
    private String tableName;
    private String columnName;
    private String dataType;
    private String columnComment;
    private Integer isPk;
    private Integer ordinalPosition;
}
