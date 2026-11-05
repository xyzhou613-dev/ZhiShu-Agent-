package com.bemodel.notice;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("bm_inspect_run")
public class InspectRun {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Integer evaluated;
    private Integer alarmed;
    private Integer noticesCreated;
    private LocalDateTime createdAt;
}
