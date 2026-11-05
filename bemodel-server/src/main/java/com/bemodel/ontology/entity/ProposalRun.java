package com.bemodel.ontology.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 提案生成运行记录（对位 bm_inspect_run）：scanned/generated/llm_failed + 全失败时的原因说明 */
@Data
@TableName("bm_proposal_run")
public class ProposalRun {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 候选 miss 数（选样后） */
    private Integer scanned;
    /** 生成提案数（同判定归并后）。列名避开 MySQL 保留字 generated */
    private Integer generatedCnt;
    /** LLM 失败/判定失效丢弃的 miss 数 */
    private Integer llmFailed;
    private String message;
    private LocalDateTime createdAt;
}
