package com.bemodel.datasource.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("bm_mapping")
public class Mapping {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String dsCode;
    private String tableName;
    private String columnName;
    private String conceptCode;
    private String attrCode;
    private String valueMap;
    private Integer confirmed;
    private String source;
    /** 生命周期（V30）: PROPOSED/ACTIVE/DEPRECATED */
    private String status;
    private String updatedBy;
    private LocalDateTime updatedAt;
    private LocalDateTime createdAt;
}
