package com.bemodel.compute;

/**
 * 字段敏感等级（M-16）。
 *
 * <p>用于算力接口响应字段和 Agent 只读投影，不等同于 M-05 的数据资产来源登记：
 * M-05 标注“数据从哪里来、谁负责”，这里标注“返回给谁、能不能给 Agent”。</p>
 */
enum ComputeSensitivity {
    /** 可公开给页面和 Agent 的资源/状态信息。 */
    PUBLIC,
    /** 平台内部使用；页面可见，Agent 只在明确列入正向清单时可见。 */
    INTERNAL,
    /** 身份、启动命令、镜像、挂载、回执原文等；默认不进入 Agent 投影。 */
    SENSITIVE
}
