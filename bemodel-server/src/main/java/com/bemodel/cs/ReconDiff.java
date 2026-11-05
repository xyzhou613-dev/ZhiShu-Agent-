package com.bemodel.cs;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 跨库核对差异登记行（V31）：差异是可查数据对象（失败三表模式），不是一次性文案。 */
@Data
@TableName("bm_recon_diff")
public class ReconDiff {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String runId;
    private String direction;
    private String stateCode;
    private String severity;
    private String attribution;
    private String suggestedAction;
    private String patientNo;
    private String orderId;
    private String itemName;
    private LocalDateTime createdAt;
}
