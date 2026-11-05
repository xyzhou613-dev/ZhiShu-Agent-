package com.bemodel.link.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("link_rel")
public class LinkRel {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String fromRefNo;
    private String toRefNo;
    private String relType;
    private String remark;
    private LocalDateTime createdAt;
}
