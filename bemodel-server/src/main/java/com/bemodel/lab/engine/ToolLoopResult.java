package com.bemodel.lab.engine;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条臂的工具循环产物。status 语义(文案按真实能力分支):
 * DONE=正常终局;FORMAT_FAILED=两次输出均不合协议(诚实呈现部分轨迹);
 * BUDGET_CUT=步数/墙钟熔断(部分轨迹卡);LLM_UNAVAILABLE=无 Key/调用失败(诚实降级,非故障)。
 */
@Data
public class ToolLoopResult {

    public enum Status { DONE, FORMAT_FAILED, BUDGET_CUT, LLM_UNAVAILABLE }

    private Status status;
    /** 仅 DONE 时非空;其余状态一律 null(不伪装结论) */
    private String answer;
    private List<ToolStep> steps = new ArrayList<>();
    private int llmCalls;
    private long elapsedMs;
}
