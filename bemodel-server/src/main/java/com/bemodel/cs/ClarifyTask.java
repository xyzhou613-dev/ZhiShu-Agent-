package com.bemodel.cs;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问数澄清任务（V32）：A 型歧义缺口的可挂起承载。
 * 任务即核验项（MVP 单问题单缺口）；resume pointer = origin_question + supplement 重入同一路由。
 */
@Data
@TableName("bm_clarify_task")
public class ClarifyTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** ANALYTICS（MVP 仅此值；CS 预留） */
    private String scene;
    /** 原问题（resume 锚点） */
    private String originQuestion;
    /** UNANSWERABLE reason（LLM 原话） */
    private String reason;
    /** 仅 AMBIGUITY 会建任务，列保留以便扩展 */
    private String gapType;
    /** 追问话术：LLM 生成，无 Key 降级模板拼装 */
    private String clarifyQuestion;
    /** PENDING / RESOLVED / GAVE_UP */
    private String status;
    /** 用户补充原文（多轮以；拼接，RESOLVED/GAVE_UP 后保留作证据） */
    private String supplement;
    /** 已发出的追问轮数，上限 2 */
    private Integer rounds;
    /** GAVE_UP 时关联回流的 bm_ontology_miss.id */
    private Long missId;
    private LocalDateTime createdAt;
    private LocalDateTime answeredAt;
    private LocalDateTime closedAt;
}
