package com.bemodel.datasource.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 映射变更留痕（V30）：CREATE/UPDATE/TRANSITION/DELETE 的前后对照 */
@Data
@TableName("bm_mapping_log")
public class MappingLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long mappingId;
    private String action;
    private String beforeJson;
    private String afterJson;
    private String operator;
    private LocalDateTime createdAt;
}
