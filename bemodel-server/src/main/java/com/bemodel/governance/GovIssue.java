package com.bemodel.governance;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("bm_gov_issue")
public class GovIssue {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long scanId;
    private String ruleCode;
    private String ruleName;
    private String ruleType;
    private String severity;
    private String conceptCode;
    private String dsCode;
    private String tableName;
    private Integer hitCount;
    private String sampleJson;
    private String status;
    private LocalDateTime createdAt;
}
