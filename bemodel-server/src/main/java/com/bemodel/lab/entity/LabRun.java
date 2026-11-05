package com.bemodel.lab.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** AI 对比实验室运行记录:一次起跑=三臂各跑一遍同题,轨迹与锚点判读整体在 payloadJson */
@Data
@TableName("bm_lab_run")
public class LabRun {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String experimentKey;
    private String question;
    private Boolean forceExecute;
    private String status;
    private String payloadJson;
    private String errorMsg;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
}
