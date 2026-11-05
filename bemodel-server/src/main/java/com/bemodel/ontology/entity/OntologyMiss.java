package com.bemodel.ontology.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 本体增长回路：词表外说法（搜索零命中 / AI 映射失败）采集池。
 * 忽略是标记不是删除（dismissed=1 计数照涨）；采纳创建 DRAFT 概念；撤销置 revoked=1 不删数据。
 */
@Data
@TableName("bm_ontology_miss")
public class OntologyMiss {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 未命中的说法原文 */
    private String term;
    /** CONCEPT/ATTRIBUTE */
    private String kind;
    /** SEARCH/MAPPING_AI/CS_ASK/QA_ASK */
    private String source;
    /** 累计出现次数 */
    private Integer count;
    /** 已忽略（0/1） */
    private Integer dismissed;
    /** 忽略理由 */
    private String dismissReason;
    /** 采纳后落的概念 code */
    private String adoptedConceptCode;
    /** 采纳形态：CONCEPT 新建概念 / TERM 挂为现有概念术语 */
    private String adoptedAs;
    /** 采纳被撤销（0/1） */
    private Integer revoked;
    private LocalDateTime firstSeen;
    private LocalDateTime lastSeen;
}
