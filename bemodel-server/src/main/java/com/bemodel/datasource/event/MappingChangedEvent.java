package com.bemodel.datasource.event;

/**
 * 映射变更事件：saveBatch/transition/updateMapping/deleteLogged 之后发布。
 * 运行时消费者（FlowService 的列值字典缓存等）监听后失效自己的缓存，
 * 保证「运行时只认 ACTIVE」的 V30 门禁不被缓存架空——生命周期变更即时生效。
 */
public record MappingChangedEvent() {
}
