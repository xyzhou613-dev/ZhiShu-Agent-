package com.bemodel.llm;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("bm_llm_log")
public class LlmLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String callType;
    /** 提供方标识（借鉴 4）："primary"|"backup"；V38 起有值，存量行回填 primary */
    private String provider;
    private String model;
    private String ontologyVersion;
    private String promptDigest;
    private Long latencyMs;
    private Integer success;
    private String errMsg;
    private LocalDateTime createdAt;
}
