package com.bemodel.cs;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bemodel.common.BizException;
import com.bemodel.common.PageResult;
import com.bemodel.cs.mapper.ClarifyTaskMapper;
import com.bemodel.llm.DeepSeekClient;
import com.bemodel.ontology.entity.OntologyMiss;
import com.bemodel.ontology.service.MissService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 澄清任务状态机（D2b）：只管任务持久化与轮次，不做问答路由（路由归 CsService，避免循环依赖）。
 *
 * 轮次语义：rounds = 已发出的追问轮数。创建即第 1 轮（rounds=1）；
 * 每次补充后若仍 A 型歧义且 rounds &lt; 2 → 第 2 轮追问；rounds 已 = 2 → 封顶 GAVE_UP。
 *
 * 诚实降级：追问话术优先 LLM 生成，失败/无 Key 落模板——A 型任务本身只在 LLM 可用时才会产生
 * （planQuery 无 Key 返回 null），所以模板分支仅覆盖「有 Key 但本轮话术生成失败」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClarifyService {

    static final int MAX_ROUNDS = 2;

    private final ClarifyTaskMapper clarifyTaskMapper;
    private final DeepSeekClient deepSeekClient;
    private final MissService missService;

    /** A 型歧义命中：建任务（第 1 轮追问），不记 miss（歧义尚未定性，问题可能被答出） */
    public ClarifyTask createTask(String scene, String originQuestion, String reason, String gapType) {
        ClarifyTask t = new ClarifyTask();
        t.setScene(scene);
        t.setOriginQuestion(truncate(originQuestion, 512));
        t.setReason(truncate(reason, 512));
        t.setGapType(gapType);
        t.setStatus("PENDING");
        t.setRounds(1);
        t.setClarifyQuestion(truncate(composeQuestion(originQuestion, reason), 512));
        t.setCreatedAt(LocalDateTime.now());
        clarifyTaskMapper.insert(t);
        return t;
    }

    /** 追问轮推进：rounds 未封顶则更新追问话术进入下一轮；已封顶返回 false（调用方走 GAVE_UP） */
    public boolean enterNextRound(ClarifyTask task, String newReason) {
        if (task.getRounds() == null || task.getRounds() >= MAX_ROUNDS) {
            return false;
        }
        task.setRounds(task.getRounds() + 1);
        task.setReason(truncate(newReason, 512));
        task.setClarifyQuestion(truncate(composeQuestion(task.getOriginQuestion(), newReason), 512));
        clarifyTaskMapper.updateById(task);
        return true;
    }

    /**
     * 维护者视图（澄清任务可见性）：任务列表。status 空/ALL → 全部；
     * 排序 PENDING→GAVE_UP→RESOLVED（FIELD 定序，非字典序），组内 id 倒序。
     * 证据在任务行：原问题 + LLM 缺什么判断 + 用户补充原文——维护者据此识别指标口径维护需求
     * （A 型歧义补充的是口径约束不是新词，与 CONCEPT/ATTRIBUTE miss 不同质，需分池呈现）。
     */
    public PageResult<ClarifyTask> page(String status, int pageNum, int pageSize) {
        LambdaQueryWrapper<ClarifyTask> qw = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            qw.eq(ClarifyTask::getStatus, status.trim().toUpperCase());
        }
        long total = clarifyTaskMapper.selectCount(qw);
        qw.last("ORDER BY FIELD(status, 'PENDING', 'GAVE_UP', 'RESOLVED'), id DESC LIMIT "
                + pageSize + " OFFSET " + (long) (pageNum - 1) * pageSize);
        List<ClarifyTask> list = clarifyTaskMapper.selectList(qw);
        return PageResult.of(list, total, pageNum, pageSize);
    }

    /** 校验可答（存在/PENDING/补充非空/重复提交拒绝），通过后把补充拼进任务（证据累积，不覆盖） */
    public ClarifyTask acceptSupplement(Long id, String supplement) {
        ClarifyTask t = clarifyTaskMapper.selectById(id);
        if (t == null) {
            throw new BizException("澄清任务不存在: " + id);
        }
        if (!"PENDING".equals(t.getStatus())) {
            throw new BizException("该澄清任务已结束（" + t.getStatus() + "），不能再补充");
        }
        String s = supplement == null ? "" : supplement.trim();
        if (s.isEmpty()) {
            throw new BizException("补充内容不能为空");
        }
        // 防重复提交：同一份补充原样再交会被拒绝（不覆盖既有证据）
        if (s.equals(t.getSupplement()) || (t.getSupplement() != null && t.getSupplement().contains("；" + s))) {
            throw new BizException("该补充内容已提交过，请换一种方式补充说明");
        }
        // 超限显式拒绝而非静默截断：截断会整段丢弃刚提交的证据且使判重失效
        if (t.getSupplement() != null && t.getSupplement().length() + 1 + s.length() > 1024) {
            throw new BizException("补充累计已达长度上限，请精简后提交，或换个问法直接提问");
        }
        t.setSupplement(t.getSupplement() == null ? s : t.getSupplement() + "；" + s);
        t.setAnsweredAt(LocalDateTime.now());
        clarifyTaskMapper.updateById(t);
        return t;
    }

    /** 澄清后真实答出：任务闭环，不记 miss（歧义澄清后答出 = 本体词表没病） */
    public void resolve(ClarifyTask task) {
        task.setStatus("RESOLVED");
        task.setClosedAt(LocalDateTime.now());
        clarifyTaskMapper.updateById(task);
    }

    /** 封顶或转定性：任务放弃，问题回流本体增长回路（miss_id 回填任务行，对话证据就在任务行里） */
    public void giveUp(ClarifyTask task) {
        task.setStatus("GAVE_UP");
        task.setClosedAt(LocalDateTime.now());
        task.setMissId(linkOrRecordMiss(task));
        clarifyTaskMapper.updateById(task);
    }

    /**
     * 回流去重：续跑转定性路径标准分支已按 q'=原问题（补充） 记过 miss，直接复用该行回填，
     * 不再新增一条（否则增长回路对同一问题出现两条提案）；A 型封顶路径标准分支未记 miss，
     * 此处按原问题补记。
     */
    private Long linkOrRecordMiss(ClarifyTask task) {
        String resumedTerm = truncate(questionWithSupplement(task), 128);
        OntologyMiss reused = missService.lambdaQuery()
                .eq(OntologyMiss::getTerm, resumedTerm)
                .eq(OntologyMiss::getKind, "QUESTION")
                .last("LIMIT 1").one();
        if (reused != null) {
            return reused.getId();
        }
        String missTerm = truncate(task.getOriginQuestion(), 128);
        missService.recordMiss(missTerm, "QUESTION", "QA_ASK");
        OntologyMiss created = missService.lambdaQuery()
                .eq(OntologyMiss::getTerm, missTerm)
                .eq(OntologyMiss::getKind, "QUESTION")
                .last("LIMIT 1").one();
        return created == null ? null : created.getId();
    }

    private static String questionWithSupplement(ClarifyTask task) {
        return task.getOriginQuestion()
                + (task.getSupplement() == null || task.getSupplement().isBlank()
                        ? "" : "（" + task.getSupplement() + "）");
    }

    /** 澄清卡视图（前端按 clarifyTask 字段存在性渲染，不做文本嗅探） */
    public Map<String, Object> view(ClarifyTask t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("question", t.getClarifyQuestion());
        m.put("originQuestion", t.getOriginQuestion());
        m.put("rounds", t.getRounds());
        return m;
    }

    /** 追问话术：LLM 生成一句话追问；失败降级模板拼装（含 reason，可行动地指出缺什么） */
    private String composeQuestion(String origin, String reason) {
        String r = reason == null || reason.isBlank() ? "口径或范围不明确" : reason;
        java.util.Optional<String> llm = deepSeekClient.chat("CLARIFY_Q",
                "你是数据问答助手的澄清器：用户的问题语义层无法直接回答，请生成一句中文追问，"
                        + "明确指出缺少什么信息（时间范围/科室/统计对象/口径），引导用户补充。只输出追问本身，不要解释。",
                "原问题：" + origin + "\n无法回答的原因：" + r);
        if (llm.isPresent() && !llm.get().isBlank()) {
            return llm.get().trim();
        }
        return "您的问题「" + truncate(origin, 120) + "」暂时无法回答（" + r
                + "）。请补充时间范围、科室或统计口径等约束，我将据此继续查询。";
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
