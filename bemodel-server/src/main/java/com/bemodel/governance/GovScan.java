package com.bemodel.governance;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("bm_gov_scan")
public class GovScan {

    @TableId(type = IdType.AUTO)
    private Long id;
    private LocalDateTime scanTime;
    private Long durationMs;
    private Integer ruleCount;
    private Integer issueCount;
    private Integer qualityScore;
}
