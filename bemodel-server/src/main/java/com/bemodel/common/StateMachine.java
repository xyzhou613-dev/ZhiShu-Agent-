package com.bemodel.common;

import java.util.Map;
import java.util.Set;

/** 建模元素统一状态机：草稿→评审→发布→废弃（评审可退回草稿，废弃可重建） */
public final class StateMachine {

    private static final Set<String> STATUS = Set.of("DRAFT", "REVIEW", "PUBLISHED", "DEPRECATED");
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "DRAFT", Set.of("REVIEW"),
            "REVIEW", Set.of("PUBLISHED", "DRAFT"),
            "PUBLISHED", Set.of("DEPRECATED"),
            "DEPRECATED", Set.of("DRAFT")
    );

    private StateMachine() {
    }

    public static void check(String current, String target) {
        if (!STATUS.contains(target)) {
            throw new BizException("非法状态: " + target);
        }
        if (!TRANSITIONS.getOrDefault(current, Set.of()).contains(target)) {
            throw new BizException("不允许从 " + current + " 流转到 " + target);
        }
    }

    /** 状态流转的中文动作名 */
    public static String actionName(String target) {
        return switch (target) {
            case "REVIEW" -> "提交评审";
            case "PUBLISHED" -> "发布";
            case "DEPRECATED" -> "废弃";
            default -> "退回草稿";
        };
    }
}
