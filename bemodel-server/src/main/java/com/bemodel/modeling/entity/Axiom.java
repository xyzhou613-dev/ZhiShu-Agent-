package com.bemodel.modeling.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("bm_axiom")
public class Axiom {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String axiomCode;
    private String subject;
    private String predicate;
    private String object;
    private String axiomType;
    private String description;
    private String status;
    private LocalDateTime createdAt;
}
