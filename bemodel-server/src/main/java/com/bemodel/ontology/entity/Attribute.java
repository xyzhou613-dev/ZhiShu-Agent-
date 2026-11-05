package com.bemodel.ontology.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("bm_attribute")
public class Attribute {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String conceptCode;
    private String attrCode;
    private String attrName;
    private String dataType;
    private Integer isKey;
    private String definition;
    private Integer sort;
}
