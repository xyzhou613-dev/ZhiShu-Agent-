package com.bemodel.lab.engine;

import lombok.Data;

/** 循环轨迹的一步(Plan 2/3 轨迹展开与判分栏的数据来源) */
@Data
public class ToolStep {

    public static final String TOOL = "TOOL";
    public static final String FINAL = "FINAL";
    public static final String FORMAT_ERROR = "FORMAT_ERROR";
    public static final String STEP_LIMIT = "STEP_LIMIT";
    public static final String BUDGET_CUT = "BUDGET_CUT";

    /** 步类型:TOOL/FINAL/FORMAT_ERROR/STEP_LIMIT/BUDGET_CUT */
    private String kind;
    /** TOOL 时为工具名 */
    private String tool;
    private String argsJson;
    private String resultJson;
    /** 模型本轮原文(审计与调试,不进前端结论) */
    private String llmRaw;
}
