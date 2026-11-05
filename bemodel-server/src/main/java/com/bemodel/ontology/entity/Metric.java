package com.bemodel.ontology.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("bm_metric")
public class Metric {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String metricCode;
    private String name;
    private String definition;
    private String formula;
    private String conceptCode;
    private String owner;
    private String dsCode;
    private String probeSql;
    private Integer warnThreshold;
    private Integer lastVal;
    private LocalDateTime lastEvalAt;
    private LocalDateTime createdAt;
}
