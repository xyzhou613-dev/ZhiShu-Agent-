package com.bemodel.ontology.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 提案队列（本体增长回路 L2：AI 提议、人裁决）。
 * 一张提案卡 = 一组同判定的 miss 聚类（miss_ids JSON）；采纳/驳回只翻提案状态，
 * miss 层标记由采纳流程经 MissService 落（与逐条人工处置同一套语义，revoke 链不变）。
 */
@Data
@TableName("bm_ontology_proposal")
public class OntologyProposal {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 主信号 miss id（组内 count 最高） */
    private Long primaryMissId;
    /** 聚类归并的全部 miss id（JSON 数组字符串，含主信号） */
    private String missIds;
    /** 代表说法（主信号原文） */
    private String term;
    /** 信号总热度（组内 count 求和） */
    private Integer signalCount;
    /** NEW_CONCEPT / ATTACH_TERM */
    private String action;
    /** ATTACH_TERM 的挂靠目标概念编码 */
    private String targetConceptCode;
    /** 候选卡 JSON 字符串：code/name/domainCode/definition/reason/confidence/warnings */
    private String suggestionJson;
    /** PENDING / ADOPTED / REJECTED */
    private String status;
    private String decidedBy;
    private LocalDateTime decidedAt;
    private String rejectReason;
    private Long runId;
    private LocalDateTime createdAt;
}
