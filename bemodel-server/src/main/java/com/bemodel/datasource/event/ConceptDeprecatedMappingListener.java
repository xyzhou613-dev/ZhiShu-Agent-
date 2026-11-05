package com.bemodel.datasource.event;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bemodel.datasource.entity.Mapping;
import com.bemodel.datasource.mapper.MappingMapper;
import com.bemodel.notice.AlertNotice;
import com.bemodel.notice.NoticeService;
import com.bemodel.ontology.event.ConceptDeprecatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 概念废弃 → 映射复查告警（维护治理加固 H3）：概念废弃时其下 ACTIVE 映射不会被自动停用
 * （V30 生命周期独立运转是刻意设计——自动停用会静默改变问数行为），改为幂等生成平台内告警，
 * 提示建模员把映射迁移到承接概念或同步置 DEPRECATED（运行时只认 ACTIVE）。
 * 便利通知不是留痕硬要求：失败只打日志，不回滚废弃主操作。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConceptDeprecatedMappingListener {

    /** 告警 metricCode 前缀：与巡检越限告警区分来源，也用作幂等键的一部分 */
    static final String METRIC_PREFIX = "CONCEPT_DEPRECATED:";

    private final MappingMapper mappingMapper;
    private final NoticeService noticeService;

    @EventListener
    public void onConceptDeprecated(ConceptDeprecatedEvent e) {
        try {
            Long active = mappingMapper.selectCount(new LambdaQueryWrapper<Mapping>()
                    .eq(Mapping::getConceptCode, e.conceptCode())
                    .eq(Mapping::getStatus, "ACTIVE"));
            if (active == null || active == 0) {
                return;
            }
            String message = "概念 " + e.conceptCode() + "（" + e.conceptName() + "）已废弃，其下仍有 " + active
                    + " 条 ACTIVE 映射：请迁移到承接概念，或将映射同步置 DEPRECATED（运行时只认 ACTIVE）。";
            // 幂等且保真：已有未读告警时不新增（防轰炸），但刷新其计数与文案——
            // 概念重建再废弃时旧告警的映射数可能已过期，陈旧计数会误导迁移进度判断
            AlertNotice unread = noticeService.getOne(new LambdaQueryWrapper<AlertNotice>()
                    .eq(AlertNotice::getMetricCode, METRIC_PREFIX + e.conceptCode())
                    .eq(AlertNotice::getStatus, "未读").last("LIMIT 1"), false);
            if (unread != null) {
                unread.setActualValue(active.intValue());
                unread.setMessage(message);
                noticeService.updateById(unread);
                return;
            }
            noticeService.createIfAbsent(METRIC_PREFIX + e.conceptCode(),
                    "概念废弃映射复查", active.intValue(), 0, message);
        } catch (Exception ex) {
            log.warn("概念废弃映射复查告警生成失败（不回滚废弃）: code={}, err={}",
                    e.conceptCode(), ex.getMessage());
        }
    }
}
