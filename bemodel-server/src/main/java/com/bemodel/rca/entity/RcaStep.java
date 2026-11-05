package com.bemodel.rca.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("rca_step")
public class RcaStep {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long caseId;
    private Integer stepNo;
    private String stepName;
    private String stepType;
    private String sqlText;
    private Integer hitCount;
    private String resultJson;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
