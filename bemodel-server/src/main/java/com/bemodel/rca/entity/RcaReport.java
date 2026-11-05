package com.bemodel.rca.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("rca_report")
public class RcaReport {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long caseId;
    private String rootCause;
    private String evidenceJson;
    private String impactJson;
    private String suggestionsJson;
    private Integer llmUsed;
    private LocalDateTime createdAt;
}
