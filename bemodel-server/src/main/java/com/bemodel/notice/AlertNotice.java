package com.bemodel.notice;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("bm_alert_notice")
public class AlertNotice {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String metricCode;
    private String metricName;
    private Integer actualValue;
    private Integer threshold;
    private String message;
    /** 未读/已读 */
    private String status;
    private LocalDateTime createdAt;
}
