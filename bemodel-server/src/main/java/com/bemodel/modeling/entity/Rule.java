package com.bemodel.modeling.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("bm_rule")
public class Rule {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String ruleCode;
    private String name;
    private String conceptCode;
    private String ruleType;
    private String expression;
    private String metricCode;
    private String severity;
    private String owner;
    private String status;
    private Integer version;
    private String engine;
    private String exprJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
