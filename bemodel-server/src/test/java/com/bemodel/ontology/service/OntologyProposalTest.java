package com.bemodel.ontology.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bemodel.common.BizException;
import com.bemodel.llm.DeepSeekClient;
import com.bemodel.ontology.entity.Concept;
import com.bemodel.ontology.entity.OntologyMiss;
import com.bemodel.ontology.entity.OntologyProposal;
import com.bemodel.ontology.entity.ProposalRun;
import com.bemodel.ontology.entity.Term;
import com.bemodel.ontology.mapper.OntologyMissMapper;
import com.bemodel.ontology.mapper.OntologyProposalMapper;
import com.bemodel.ontology.mapper.ProposalRunMapper;
import com.bemodel.ontology.mapper.TermMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 本体提案生成器端到端（spec: 2026-09-16-ontology-proposal-engine-design）：
 * 选样规则（只取待处理 CONCEPT、排除 PENDING 提案引用、热度降序、Top-N 截断）；
 * 生成归并（同判定聚类）与 LLM 不可用诚实降级（@MockBean DeepSeekClient 确定性化）；
 * 裁决闭环（采纳=复用 miss 采纳机制建 DRAFT/挂术语；驳回=释放信号可再入队；不可重复裁决）。
 * 演示库直连不回滚：清理一律按「提案测试」前缀 + run id 水位线精确删除。
 */
@SpringBootTest
class OntologyProposalTest {

    private static final String PREFIX = "提案测试";
    private static final String CODE = "TEST_PROP_CONCEPT";
    private static final String NEW_CODE = "TEST_PROP_NEW";

    @Autowired
    private OntologyProposalService proposalService;
    @Autowired
    private OntologyProposalMapper proposalMapper;
    @Autowired
    private ProposalRunMapper runMapper;
    @Autowired
    private OntologyMissMapper missMapper;
    @Autowired
    private ConceptService conceptService;
    @Autowired
    private com.bemodel.ontology.service.MissService missService;
    @Autowired
    private TermMapper termMapper;
    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @MockBean
    private DeepSeekClient deepSeekClient;

    private long runWatermark;

    @BeforeEach
    void setUp() {
        runWatermark = runMapper.selectList(null).stream()
                .map(ProposalRun::getId).max(Long::compareTo).orElse(0L);
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        missMapper.delete(new LambdaQueryWrapper<OntologyMiss>()
                .likeRight(OntologyMiss::getTerm, PREFIX));
        proposalMapper.delete(new LambdaQueryWrapper<OntologyProposal>()
                .likeRight(OntologyProposal::getTerm, PREFIX));
        runMapper.delete(new LambdaQueryWrapper<ProposalRun>()
                .gt(ProposalRun::getId, runWatermark));
        termMapper.delete(new LambdaQueryWrapper<Term>()
                .likeRight(Term::getTerm, PREFIX));
        conceptService.lambdaUpdate().eq(Concept::getCode, NEW_CODE).remove();
        conceptService.lambdaUpdate().eq(Concept::getCode, CODE).remove();
        // 安全网：万一桩外信号被误挂标记/误建术语，按测试独有编码精确释放（不影响任何真实数据）
        termMapper.delete(new LambdaQueryWrapper<Term>()
                .eq(Term::getSourceProduct, "ONTOLOGY_MISS").eq(Term::getConceptCode, CODE));
        for (String code : List.of(NEW_CODE, CODE)) {
            missMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<OntologyMiss>()
                    .eq(OntologyMiss::getAdoptedConceptCode, code)
                    .set(OntologyMiss::getAdoptedConceptCode, null)
                    .set(OntologyMiss::getAdoptedAs, null)
                    .set(OntologyMiss::getRevoked, 0));
        }
    }

    private static void loginAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "tester", "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private OntologyMiss seed(String suffix, int count) {
        OntologyMiss m = new OntologyMiss();
        m.setTerm(PREFIX + suffix);
        m.setKind("CONCEPT");
        m.setSource("SEARCH");
        m.setCount(count);
        m.setDismissed(0);
        m.setRevoked(0);
        missMapper.insert(m);
        return m;
    }

    private void draftTarget() {
        Concept c = new Concept();
        c.setCode(CODE);
        c.setName("测试挂靠目标");
        c.setDomainCode("OPS");
        conceptService.create(c);
    }

    /**
     * 带守卫的桩：只对含本测试前缀的提示词给有效判定，其余（演示库既有 miss）返回非法 action
     * 使其走失效丢弃路径——把副作用严格圈在本测试种下的信号里，不污染演示数据。
     */
    private void stubCardForOurs(String json) {
        when(deepSeekClient.chat(eq("MISS_PROPOSAL"), any(), any())).thenAnswer(inv -> {
            String prompt = inv.getArgument(2);
            return prompt != null && prompt.contains(PREFIX)
                    ? Optional.of(json)
                    : Optional.of("{\"action\":\"UNKNOWN\"}");
        });
    }

    private void stubNewConcept() {
        stubCardForOurs("{\"action\":\"NEW_CONCEPT\",\"code\":\"" + NEW_CODE + "\",\"name\":\"测试提案概念\","
                + "\"domainCode\":\"OPS\",\"definition\":\"测试定义\",\"reason\":\"测试理由\",\"confidence\":0.8}");
    }

    private void stubAttach() {
        stubCardForOurs("{\"action\":\"ATTACH_TERM\",\"targetConceptCode\":\"" + CODE + "\","
                + "\"reason\":\"已有概念就是归宿\",\"confidence\":0.9}");
    }

    private OntologyProposal pendingProposal() {
        List<OntologyProposal> ps = proposalMapper.selectList(new LambdaQueryWrapper<OntologyProposal>()
                .eq(OntologyProposal::getStatus, "PENDING")
                .likeRight(OntologyProposal::getTerm, PREFIX));
        assertEquals(1, ps.size(), "应恰好产生一张待裁决提案");
        return ps.get(0);
    }

    // ---------- 选样规则 ----------

    @Test
    void 选样_只取待处理CONCEPT_排除被待裁决提案引用_热度降序与TopN截断() {
        // 高计数字段保证本测试信号压过演示库活水（miss 池随真实使用增长，对抗评审确认
        // 依赖「种子必进全局 Top-10」的隐含前提会以假回归方式翻红）
        OntologyMiss hot = seed("热", 500);
        OntologyMiss warm = seed("温", 300);
        OntologyMiss cold = seed("冷", 100);
        OntologyMiss revoked = seed("已撤销回池", 200);
        revoked.setAdoptedConceptCode("X");
        revoked.setAdoptedAs("CONCEPT");
        revoked.setRevoked(1);
        missMapper.updateById(revoked);

        OntologyMiss dismissed = seed("已忽略", 9);
        dismissed.setDismissed(1);
        missMapper.updateById(dismissed);
        OntologyMiss adopted = seed("已采纳", 9);
        adopted.setAdoptedConceptCode("X");
        adopted.setAdoptedAs("CONCEPT");
        missMapper.updateById(adopted);
        OntologyMiss attr = seed("属性类", 9);
        attr.setKind("ATTRIBUTE");
        missMapper.updateById(attr);
        OntologyMiss blocked = seed("已被提案占用", 9);
        OntologyProposal p = new OntologyProposal();
        p.setPrimaryMissId(blocked.getId());
        p.setMissIds("[" + blocked.getId() + "]");
        p.setTerm(blocked.getTerm());
        p.setSignalCount(9);
        p.setAction("NEW_CONCEPT");
        p.setStatus("PENDING");
        p.setRunId(runWatermark);
        proposalMapper.insert(p);

        List<Long> picked = proposalService.selectCandidateMisses().stream()
                .map(OntologyMiss::getId).toList();
        // 高计数字段保证本测试信号在 Top-N 内（演示库既有 miss 计数为个位数）
        assertTrue(picked.contains(hot.getId()));
        assertTrue(picked.contains(warm.getId()));
        assertTrue(picked.contains(cold.getId()));
        assertTrue(picked.contains(revoked.getId()), "采纳已撤销的信号回池，重新可选");
        assertFalse(picked.contains(dismissed.getId()), "已忽略不入选");
        assertFalse(picked.contains(adopted.getId()), "已有效采纳不入选");
        assertFalse(picked.contains(blocked.getId()), "被 PENDING 提案引用（含非主信号）不重复入队");
        assertTrue(picked.indexOf(hot.getId()) < picked.indexOf(warm.getId())
                && picked.indexOf(warm.getId()) < picked.indexOf(revoked.getId())
                && picked.indexOf(revoked.getId()) < picked.indexOf(cold.getId()), "热度降序");

        // Top-N 截断：本测试信号入选数不超过上限（默认 10）
        for (int i = 0; i < 8; i++) {
            seed("填充" + i, 0);
        }
        long ours = proposalService.selectCandidateMisses().stream()
                .filter(m -> m.getTerm().startsWith(PREFIX)).count();
        assertTrue(ours <= 10, "Top-N 截断生效（默认 10），实际入选: " + ours);
    }

    // ---------- 生成与采纳（LLM 桩：确定性全管线） ----------

    @Test
    void 生成_同判定归并聚类_采纳为草稿概念_全组信号闭环() throws Exception {
        loginAs("ADMIN");
        OntologyMiss a = seed("甲", 3);
        OntologyMiss b = seed("乙", 7);
        stubNewConcept();

        Map<String, Object> res = proposalService.run();
        int scanned = (int) res.get("scanned");
        assertTrue(scanned >= 2, "演示库可能还有其他待处理信号，只断言下限");
        assertEquals(1, res.get("generated"), "同判定同编码应归并为一张提案（桩外信号失效丢弃）");
        assertEquals(scanned - 2, res.get("llmFailed"), "桩外信号走失效丢弃路径逐条计数");

        OntologyProposal p = pendingProposal();
        assertEquals(b.getId(), p.getPrimaryMissId(), "主信号取组内热度最高");
        assertEquals(b.getTerm(), p.getTerm());
        List<?> parsed = objectMapper.readValue(p.getMissIds(), List.class);
        assertEquals(List.of(b.getId(), a.getId()),
                parsed.stream().map(n -> ((Number) n).longValue()).toList(),
                "选样热度降序：乙(7)在甲(3)前");
        assertEquals(10, p.getSignalCount(), "信号热度求和");
        assertEquals("NEW_CONCEPT", p.getAction());

        OntologyProposal decided = proposalService.adopt(p.getId());
        assertEquals("ADOPTED", decided.getStatus());
        assertEquals("tester", decided.getDecidedBy());
        assertNotNull(decided.getDecidedAt());

        Concept c = conceptService.getByCode(NEW_CODE);
        assertNotNull(c, "采纳应创建概念");
        assertEquals("DRAFT", c.getStatus(), "自动化只建草稿，不发布");
        assertEquals("测试提案概念", c.getName());
        for (OntologyMiss m : List.of(a, b)) {
            OntologyMiss after = missMapper.selectById(m.getId());
            assertEquals(NEW_CODE, after.getAdoptedConceptCode(), "全组信号一并闭环");
            assertEquals("CONCEPT", after.getAdoptedAs());
            assertEquals(0, after.getRevoked());
        }
    }

    @Test
    void 采纳_挂靠术语_并入既有概念方言() {
        loginAs("ADMIN");
        draftTarget();
        OntologyMiss a = seed("说法一", 2);
        OntologyMiss b = seed("说法二", 4);
        stubAttach();

        Map<String, Object> res = proposalService.run();
        assertEquals(1, res.get("generated"));
        OntologyProposal p = pendingProposal();
        assertEquals("ATTACH_TERM", p.getAction());
        assertEquals(CODE, p.getTargetConceptCode());
        assertEquals(6, p.getSignalCount());

        proposalService.adopt(p.getId());
        assertEquals("ADOPTED", proposalService.getById(p.getId()).getStatus());
        for (OntologyMiss m : List.of(a, b)) {
            Term t = termMapper.selectOne(new LambdaQueryWrapper<Term>()
                    .eq(Term::getTerm, m.getTerm())
                    .eq(Term::getSourceProduct, "ONTOLOGY_MISS").last("LIMIT 1"));
            assertNotNull(t, "术语行应创建: " + m.getTerm());
            assertEquals(CODE, t.getConceptCode());
            assertEquals("TERM", missMapper.selectById(m.getId()).getAdoptedAs());
        }
    }

    @Test
    void 生成_LLM不可用_诚实降级不编提案() {
        seed("信号一", 1);
        seed("信号二", 2);
        when(deepSeekClient.chat(eq("MISS_PROPOSAL"), any(), any())).thenReturn(Optional.empty());

        Map<String, Object> res = proposalService.run();
        int scanned = (int) res.get("scanned");
        assertTrue(scanned >= 2, "演示库可能还有其他待处理信号，只断言下限");
        assertEquals(0, res.get("generated"));
        assertEquals(scanned, res.get("llmFailed"), "LLM 失败逐条计数");

        ProposalRun run = runMapper.selectById((Long) res.get("runId"));
        assertNotNull(run);
        assertTrue(run.getMessage() != null && run.getMessage().contains("LLM 不可用"),
                "全失败须留痕说明，实际: " + run.getMessage());
        assertEquals(0, proposalMapper.selectCount(new LambdaQueryWrapper<OntologyProposal>()
                .eq(OntologyProposal::getRunId, run.getId())), "不编造提案");
    }

    @Test
    void 驳回_释放信号可再入队_已裁决不可重复操作() {
        loginAs("EDITOR");
        OntologyMiss a = seed("会回来的", 1);
        stubNewConcept();
        proposalService.run();
        OntologyProposal p = pendingProposal();

        OntologyProposal decided = proposalService.reject(p.getId(), "口径不对");
        assertEquals("REJECTED", decided.getStatus());
        assertEquals("tester", decided.getDecidedBy());
        assertEquals("口径不对", decided.getRejectReason());

        assertTrue(proposalService.selectCandidateMisses().stream()
                        .anyMatch(m -> m.getId().equals(a.getId())),
                "驳回只对提案说不，信号仍留在池中可再入队");

        assertThrows(BizException.class, () -> proposalService.adopt(p.getId()), "已裁决不可再采纳");
        assertThrows(BizException.class, () -> proposalService.reject(p.getId(), "再驳"), "已裁决不可再驳回");
    }

    @Test
    void 采纳_候选卡损坏_报错且不落采纳() {
        loginAs("ADMIN");
        OntologyMiss a = seed("坏卡", 1);
        OntologyProposal p = new OntologyProposal();
        p.setPrimaryMissId(a.getId());
        p.setMissIds("[" + a.getId() + "]");
        p.setTerm(a.getTerm());
        p.setSignalCount(1);
        p.setAction("NEW_CONCEPT");
        p.setStatus("PENDING");
        p.setRunId(runWatermark);
        p.setSuggestionJson("{}");
        proposalMapper.insert(p);

        assertThrows(BizException.class, () -> proposalService.adopt(p.getId()));
        assertNull(missMapper.selectById(a.getId()).getAdoptedConceptCode(), "失败采纳不留 miss 标记");
    }

    // ---------- 对抗评审确认缺陷的回归钉 ----------

    @Test
    void 采纳_组内信号已被人工先行处置_结构化报错且提案保持待裁决() {
        loginAs("ADMIN");
        OntologyMiss a = seed("被人工抢走", 1);
        OntologyProposal p = new OntologyProposal();
        p.setPrimaryMissId(a.getId());
        p.setMissIds("[" + a.getId() + "]");
        p.setTerm(a.getTerm());
        p.setSignalCount(1);
        p.setAction("NEW_CONCEPT");
        p.setStatus("PENDING");
        p.setRunId(runWatermark);
        p.setSuggestionJson("{\"action\":\"NEW_CONCEPT\",\"code\":\"" + NEW_CODE
                + "\",\"name\":\"测试提案概念\",\"domainCode\":\"OPS\"}");
        proposalMapper.insert(p);

        // 选样守卫是单向的：PENDING 期间人工仍可在 miss 看板直接采纳该信号（反向路径）
        missService.attachAdoption(a.getId(), NEW_CODE);

        BizException ex = assertThrows(BizException.class, () -> proposalService.adopt(p.getId()));
        assertTrue(ex.getMessage().contains("已被人工处置"), "实际: " + ex.getMessage());
        assertEquals("PENDING", proposalService.getById(p.getId()).getStatus(), "失败采纳随事务回滚，提案保持待裁决");
        assertNull(conceptService.getByCode(NEW_CODE), "不得产生半采纳产物");
    }

    @Test
    void 撤销_归并采纳的共享概念_仅最后一名撤销者废弃概念() {
        loginAs("ADMIN");
        Concept c = new Concept();
        c.setCode(NEW_CODE);
        c.setName("共享概念");
        c.setDomainCode("OPS");
        conceptService.create(c);
        OntologyMiss a = seed("成员一", 1);
        OntologyMiss b = seed("成员二", 2);
        missService.attachAdoption(a.getId(), NEW_CODE);
        missService.attachAdoption(b.getId(), NEW_CODE);

        missService.revoke(a.getId());
        assertEquals("DRAFT", conceptService.getByCode(NEW_CODE).getStatus(),
                "仍有未撤销引用者，概念不得随单个成员撤销而废弃");
        missService.revoke(b.getId());
        assertEquals("DEPRECATED", conceptService.getByCode(NEW_CODE).getStatus(),
                "最后一名撤销者触发概念废弃");
    }
}
