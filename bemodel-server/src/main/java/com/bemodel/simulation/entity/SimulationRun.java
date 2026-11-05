package com.bemodel.simulation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 推演沙盘运行记录(bm_simulation_run,V36):形制同 bm_lab_run,payload 完成时一次写 */
@Data
@TableName("bm_simulation_run")
public class SimulationRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 场景键 STOCK_CUT */
    private String scenario;

    /** QUEUED/RUNNING/DONE/FAILED */
    private String status;

    /** 施加+波及步骤+观察 diff 整体一次写 */
    private String payloadJson;

    private String errorMsg;

    private LocalDateTime createdAt;

    private LocalDateTime finishedAt;
}
