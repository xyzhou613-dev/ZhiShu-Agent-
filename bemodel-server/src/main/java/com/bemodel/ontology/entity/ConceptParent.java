package com.bemodel.ontology.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 概念多父继承（subClassOf 结构化）。is_primary 只管展示主父；
 * 加父时服务层拒绝成环，全图环由 OntologyCheckService 的 SUBCLASS_CYCLE 检查兜底。
 */
@Data
@TableName("bm_concept_parent")
public class ConceptParent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String childCode;
    private String parentCode;
    /** 主父（展示用，同 child 唯一）：0/1 */
    private Integer isPrimary;
    private LocalDateTime createdAt;
}
