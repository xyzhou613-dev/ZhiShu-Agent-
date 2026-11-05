package com.bemodel.lab.engine;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 实验室工具 SPI(Plan 1):A/B 两臂的工具都实现此接口。
 * execute 返回结构化事实(数字/状态一律来自真实执行),循环器只负责把它原样回喂给模型——
 * 「LLM 只表达不编数」的机制保证:模型的话语被限制在工具返回的事实之内。
 * 实现注意:args 里的值进 SQL 一律参数化(?占位),不拼字符串。
 */
public interface LabTool {

    /** 工具名(模型调用时引用,小写下划线) */
    String name();

    /** 一句话说明(进工具清单) */
    String description();

    /** 参数说明(JSON Schema 子集文本,进工具清单) */
    String argsSchema();

    /** 执行:入参为模型给的结构化 args;异常由循环器捕获转为 error 观察项回喂 */
    Map<String, Object> execute(JsonNode args);
}
