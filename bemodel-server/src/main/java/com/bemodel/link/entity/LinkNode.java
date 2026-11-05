package com.bemodel.link.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("link_node")
public class LinkNode {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String nodeType;
    private String refNo;
    private String title;
    private String conceptCode;
    private String status;
    private LocalDateTime occurredAt;
    private String payload;
    private LocalDateTime createdAt;
}
