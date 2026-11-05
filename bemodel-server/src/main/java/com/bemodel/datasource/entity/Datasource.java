package com.bemodel.datasource.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("bm_datasource")
public class Datasource {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String dsCode;
    private String dsName;
    private String productName;
    private String dbType;
    private String host;
    private Integer port;
    private String dbName;
    private String username;
    private String password;
    private LocalDateTime createdAt;
}
