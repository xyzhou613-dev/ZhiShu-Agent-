package com.bemodel.ontology.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("bm_term")
public class Term {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String term;
    private String conceptCode;
    private String sourceProduct;
    private String termType;
    private String codeSystem;
    private String standardCode;
}
