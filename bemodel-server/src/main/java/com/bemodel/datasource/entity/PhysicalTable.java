package com.bemodel.datasource.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("bm_physical_table")
public class PhysicalTable {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String dsCode;
    private String tableName;
    private String tableComment;
    private LocalDateTime scannedAt;
}
