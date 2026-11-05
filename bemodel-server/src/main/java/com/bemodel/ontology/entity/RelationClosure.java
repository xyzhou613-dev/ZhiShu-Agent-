package com.bemodel.ontology.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 传递关系闭包（发布时物化）：relation_name 下 from 经传递可达 to，depth 为最短跳数。
 */
@Data
@TableName("bm_relation_closure")
public class RelationClosure {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String relationName;
    private String fromConcept;
    private String toConcept;
    private Integer depth;
}
