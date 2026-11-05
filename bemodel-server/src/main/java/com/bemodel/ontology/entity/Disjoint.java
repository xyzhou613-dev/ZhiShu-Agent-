package com.bemodel.ontology.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 概念互斥公理（结构化，机器可查）。
 * 对称语义：存储时按 code 字典序规范化为单行，查询由服务层做 A-B/B-A 双向匹配。
 */
@Data
@TableName("bm_concept_disjoint")
public class Disjoint {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String conceptACode;
    private String conceptBCode;
    /** 互斥理由（来源公理/业务口径） */
    private String definition;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
