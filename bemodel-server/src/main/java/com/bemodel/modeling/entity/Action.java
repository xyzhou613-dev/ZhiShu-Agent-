package com.bemodel.modeling.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("bm_action")
public class Action {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String actionCode;
    private String name;
    private String conceptCode;
    private String fromStatus;
    private String toStatus;
    private String triggerDesc;
    private String description;
    private String status;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
