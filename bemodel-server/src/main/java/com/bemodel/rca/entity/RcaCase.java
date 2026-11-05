package com.bemodel.rca.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("rca_case")
public class RcaCase {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String caseNo;
    private String ticketRef;
    private String conceptCode;
    private String status;
    private String conclusion;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
}
