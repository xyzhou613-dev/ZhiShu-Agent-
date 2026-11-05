package com.bemodel.ontology.event;

/**
 * 概念废弃事件：transition 到 DEPRECATED 时发布（含 miss 撤销采纳路径的直达废弃）。
 * 与 MappingChangedEvent 同一模式：进程内同步，消费者自查下游并自行降级——
 * 监听器失败不得回滚废弃本身（废弃是主操作，联动是便利通知，与映射留痕的回滚语义刻意区分）。
 */
public record ConceptDeprecatedEvent(String conceptCode, String conceptName) {
}
