package com.bemodel.clinical;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("qc_result")
public class QcResult {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String recordId;
    private String inhosNo;
    private String recordType;
    private Integer passFlag;
    private String findingsJson;
    private String traceJson;
    private Integer llmUsed;
    private String llmSummary;
    private LocalDateTime createdAt;
}
