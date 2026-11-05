package com.bemodel.notice;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bemodel.common.PageResult;
import com.bemodel.notice.mapper.AlertNoticeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 平台内告警：未读数/分页/已读；生成走幂等（同指标有未读则不重复） */
@Service
@RequiredArgsConstructor
public class NoticeService extends ServiceImpl<AlertNoticeMapper, AlertNotice> {

    public long unreadCount() {
        return count(new LambdaQueryWrapper<AlertNotice>().eq(AlertNotice::getStatus, "未读"));
    }

    public PageResult<AlertNotice> page(int pageNum, int pageSize) {
        long total = count();
        List<AlertNotice> list = list(new LambdaQueryWrapper<AlertNotice>()
                .orderByAsc(AlertNotice::getStatus)   // 未读（"未读"字典序先于"已读"）在前
                .orderByDesc(AlertNotice::getId)
                .last("LIMIT " + pageSize + " OFFSET " + (pageNum - 1) * pageSize));
        return PageResult.of(list, total, pageNum, pageSize);
    }

    public void markRead(Long id) {
        AlertNotice n = getById(id);
        if (n != null) {
            n.setStatus("已读");
            updateById(n);
        }
    }

    public long markAllRead() {
        List<AlertNotice> unread = list(new LambdaQueryWrapper<AlertNotice>()
                .eq(AlertNotice::getStatus, "未读"));
        unread.forEach(n -> {
            n.setStatus("已读");
            updateById(n);
        });
        return unread.size();
    }

    /** 幂等生成：同一指标存在未读告警时不重复（防告警轰炸）。返回是否新建 */
    public boolean createIfAbsent(String metricCode, String metricName,
                                  Integer actualValue, Integer threshold, String message) {
        Long exists = baseMapper.selectCount(new LambdaQueryWrapper<AlertNotice>()
                .eq(AlertNotice::getMetricCode, metricCode)
                .eq(AlertNotice::getStatus, "未读"));
        if (exists != null && exists > 0) {
            return false;
        }
        AlertNotice n = new AlertNotice();
        n.setMetricCode(metricCode);
        n.setMetricName(metricName);
        n.setActualValue(actualValue);
        n.setThreshold(threshold);
        n.setMessage(message);
        n.setStatus("未读");
        save(n);
        return true;
    }
}
