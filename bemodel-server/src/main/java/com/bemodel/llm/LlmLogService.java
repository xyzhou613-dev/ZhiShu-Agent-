package com.bemodel.llm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bemodel.common.PageResult;
import com.bemodel.llm.mapper.LlmLogMapper;
import com.bemodel.modeling.service.ReleaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** LLM 调用审计：每次调用记录所用模型、本体版本、耗时与成败，支撑效果回溯与版本复现 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmLogService {

    private final LlmLogMapper llmLogMapper;
    private final ReleaseService releaseService;

    /** 每次尝试一行（借鉴 4）：provider 区分主备路（"primary"|"backup"）；无 Key 行同走此入口 */
    public void log(String callType, String model, String provider, String promptDigest,
                    long latencyMs, boolean success, String errMsg) {
        try {
            LlmLog entry = new LlmLog();
            entry.setCallType(callType);
            entry.setProvider(provider == null || provider.isBlank() ? "primary" : provider);
            entry.setModel(model);
            String tag = releaseService.currentTag();
            entry.setOntologyVersion(tag == null ? "未发布" : tag);
            entry.setPromptDigest(promptDigest == null ? ""
                    : promptDigest.substring(0, Math.min(promptDigest.length(), 200)));
            entry.setLatencyMs(latencyMs);
            entry.setSuccess(success ? 1 : 0);
            entry.setErrMsg(errMsg);
            llmLogMapper.insert(entry);
        } catch (Exception e) {
            log.warn("LLM 调用日志写入失败（不影响主流程）: {}", e.getMessage());
        }
    }

    public List<LlmLog> recent() {
        return llmLogMapper.selectList(new LambdaQueryWrapper<LlmLog>()
                .orderByDesc(LlmLog::getId).last("LIMIT 100"));
    }

    /** 调用日志服务端分页 */
    public PageResult<LlmLog> page(int pageNum, int pageSize) {
        long total = llmLogMapper.selectCount(null);
        List<LlmLog> list = llmLogMapper.selectList(new LambdaQueryWrapper<LlmLog>()
                .orderByDesc(LlmLog::getId)
                .last("LIMIT " + pageSize + " OFFSET " + (pageNum - 1) * pageSize));
        return PageResult.of(list, total, pageNum, pageSize);
    }

    public Map<String, Object> stats() {
        List<LlmLog> all = llmLogMapper.selectList(null);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", all.size());
        stats.put("successRate", all.isEmpty() ? 0 :
                Math.round(all.stream().filter(l -> l.getSuccess() == 1).count() * 100.0 / all.size()));
        stats.put("byType", all.stream().collect(Collectors.groupingBy(
                LlmLog::getCallType, Collectors.counting())));
        stats.put("avgLatencyMs", all.isEmpty() ? 0 :
                Math.round(all.stream().mapToLong(LlmLog::getLatencyMs).average().orElse(0)));
        stats.put("currentOntologyVersion",
                releaseService.currentTag() == null ? "未发布" : releaseService.currentTag());
        return stats;
    }
}
