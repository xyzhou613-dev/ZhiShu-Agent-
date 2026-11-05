package com.bemodel.cs;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("bm_cs_feedback")
public class CsFeedback {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String question;
    /** 当时命中的意图标签 */
    private String intent;
    /** LLM/RULE/SEMANTIC/NONE */
    private String router;
    /** 1 归类正确 / 0 错误 */
    private Integer correct;
    private String comment;
    private LocalDateTime createdAt;
}
