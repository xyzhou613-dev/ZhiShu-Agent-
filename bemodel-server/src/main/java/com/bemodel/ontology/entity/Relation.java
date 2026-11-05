package com.bemodel.ontology.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("bm_relation")
public class Relation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String fromConcept;
    private String toConcept;
    private String relationName;
    private String description;
    /** 对称公理 */
    private Integer isSymmetric;
    /** 传递公理（partOf/属于类） */
    private Integer isTransitive;
    /** 函数公理：同一主语至多一个宾语 */
    private Integer isFunctional;
    /** 逆函数公理：同一宾语至多一个主语 */
    private Integer isInverseFunctional;
    /** 反对称公理 */
    private Integer isAsymmetric;
    /** 互逆关系的 relation_name */
    private String inverseOf;
    /** 外部本体 IRI（OWL 导入对齐用） */
    private String iri;
}
