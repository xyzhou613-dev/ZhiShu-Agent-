package com.bemodel.ontology.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("bm_concept")
public class Concept {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String domainCode;
    private String definition;
    private String owner;
    private String status;
    private Integer version;
    /** 外部本体 IRI（OWL 导入对齐用，与平台内部 code 分离） */
    private String iri;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
