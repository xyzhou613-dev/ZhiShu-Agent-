package com.bemodel.datasource;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bemodel.datasource.entity.PhysicalColumn;
import com.bemodel.datasource.mapper.PhysicalColumnMapper;
import com.bemodel.datasource.service.MappingService;
import com.bemodel.llm.DeepSeekClient;
import com.bemodel.ontology.entity.Attribute;
import com.bemodel.ontology.entity.Concept;
import com.bemodel.ontology.entity.OntologyMiss;
import com.bemodel.ontology.mapper.AttributeMapper;
import com.bemodel.ontology.mapper.ConceptMapper;
import com.bemodel.ontology.mapper.OntologyMissMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * F1-A/C 端到端（spec: 2026-09-16-llm-timeout-budget-design）：列分批（batch-size=2）
 * 逐批 LLM 调用、逐批 Top-K 预筛（top-k-concepts=1 只送最高分概念）、建议跨批合并；
 * 全部批次失败整体降级规则建议，且无候选列照常采集 ATTRIBUTE miss。
 * 演示库直连不回滚：概念/列/miss 均按测试前缀清理；预筛断言只看种子概念的在场与否，
 * 不依赖演示本体规模（活水不敏感）。
 */
@SpringBootTest(properties = {
        "bemodel.mapping.llm-batch-size=2",
        "bemodel.mapping.top-k-concepts=1"})
class MappingSuggestBatchTest {

    private static final String DS = "TESTF1DS";
    private static final String TABLE = "TESTF1_T";
    private static final String CODE_A = "F1TEST_A";
    private static final String CODE_B = "F1TEST_B";
    private static final String MISS_PREFIX = "测试专用";

    @Autowired
    private MappingService mappingService;
    @Autowired
    private PhysicalColumnMapper columnMapper;
    @Autowired
    private ConceptMapper conceptMapper;
    @Autowired
    private AttributeMapper attributeMapper;
    @Autowired
    private OntologyMissMapper missMapper;

    @MockBean
    private DeepSeekClient deepSeekClient;

    private final List<String> prompts = new ArrayList<>();

    @BeforeEach
    void seed() {
        cleanup();
        for (String[] c : new String[][]{
                {"TEST_COL_A1", "varchar", MISS_PREFIX + "甲概念字段编号一"},
                {"TEST_COL_A2", "varchar", MISS_PREFIX + "甲概念说明二"},
                {"TEST_COL_B1", "varchar", MISS_PREFIX + "乙概念字段编号一"},
                {"TEST_COL_B2", "varchar", MISS_PREFIX + "乙概念说明二"}}) {
            PhysicalColumn pc = new PhysicalColumn();
            pc.setDsCode(DS);
            pc.setTableName(TABLE);
            pc.setColumnName(c[0]);
            pc.setDataType(c[1]);
            pc.setColumnComment(c[2]);
            pc.setIsPk(0);
            pc.setOrdinalPosition(columnMapper.selectCount(new LambdaQueryWrapper<PhysicalColumn>()
                    .eq(PhysicalColumn::getDsCode, DS)).intValue() + 1);
            columnMapper.insert(pc);
        }
        concept(CODE_A, "测试专用甲概念", "F1_A1", "字段编号");
        concept(CODE_B, "测试专用乙概念", "F1_B1", "字段编号");
    }

    private void concept(String code, String name, String attrCode, String attrName) {
        Concept c = new Concept();
        c.setCode(code);
        c.setName(name);
        c.setDomainCode("OPS");
        c.setStatus("PUBLISHED");
        conceptMapper.insert(c);
        Attribute a = new Attribute();
        a.setConceptCode(code);
        a.setAttrCode(attrCode);
        a.setAttrName(attrName);
        a.setDataType("STRING");
        attributeMapper.insert(a);
    }

    @AfterEach
    void cleanup() {
        columnMapper.delete(new LambdaQueryWrapper<PhysicalColumn>().eq(PhysicalColumn::getDsCode, DS));
        for (String code : List.of(CODE_A, CODE_B)) {
            attributeMapper.delete(new LambdaQueryWrapper<Attribute>().eq(Attribute::getConceptCode, code));
            conceptMapper.delete(new LambdaQueryWrapper<Concept>().eq(Concept::getCode, code));
        }
        missMapper.delete(new LambdaQueryWrapper<OntologyMiss>()
                .likeRight(OntologyMiss::getTerm, MISS_PREFIX));
    }

    /** 批感知桩：提示词里出现哪个概念编码，就返回该批列的建议——两批各一批一答 */
    private void stubBatchAware() {
        when(deepSeekClient.chat(eq("MAPPING_SUGGEST"), any(), any())).thenAnswer(inv -> {
            String prompt = inv.getArgument(2);
            prompts.add(prompt == null ? "" : prompt);
            if (prompt != null && prompt.contains(CODE_A)) {
                return Optional.of("[{\"column\":\"TEST_COL_A1\",\"conceptCode\":\"" + CODE_A + "\","
                        + "\"attrCode\":\"F1_A1\",\"confidence\":0.9,\"reason\":\"测试\"}]");
            }
            if (prompt != null && prompt.contains(CODE_B)) {
                return Optional.of("[{\"column\":\"TEST_COL_B1\",\"conceptCode\":\"" + CODE_B + "\","
                        + "\"attrCode\":\"F1_B1\",\"confidence\":0.9,\"reason\":\"测试\"}]");
            }
            return Optional.of("[]");
        });
        when(deepSeekClient.model()).thenReturn("mock-model");
    }

    // ---------- 端到端 ----------

    @Test
    void 分批_逐批预筛_建议跨批合并() {
        stubBatchAware();
        Map<String, Object> res = mappingService.aiSuggest(DS, TABLE);

        assertEquals(Boolean.TRUE, res.get("llmUsed"), "两批均有产出，llmUsed=true");
        assertEquals(2, prompts.size(), "4 列按 batch-size=2 切两批，逐批调用");
        // 批内列成员断言（对抗评审）：buildPrompt 误传全量 columns 时这里必翻红
        assertTrue(prompts.get(0).contains("TEST_COL_A1") && prompts.get(0).contains("TEST_COL_A2"),
                "第 1 批提示词含本批全部列名");
        assertFalse(prompts.get(0).contains("TEST_COL_B1") && prompts.get(0).contains("TEST_COL_B2"),
                "第 1 批提示词不含下批列名");
        assertTrue(prompts.get(1).contains("TEST_COL_B1") && prompts.get(1).contains("TEST_COL_B2"),
                "第 2 批提示词含本批全部列名");
        assertFalse(prompts.get(1).contains("TEST_COL_A1") && prompts.get(1).contains("TEST_COL_A2"),
                "第 2 批提示词不含上批列名");
        assertTrue(prompts.get(0).contains(CODE_A) && !prompts.get(0).contains(CODE_B),
                "第 1 批只送甲概念（Top-K=1），不含乙概念——批间候选不串扰");
        assertTrue(prompts.get(1).contains(CODE_B) && !prompts.get(1).contains(CODE_A),
                "第 2 批只送乙概念");
        assertTrue(prompts.get(0).contains("预筛") && prompts.get(1).contains("预筛"),
                "预筛生效时提示词如实告知候选非全量");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> suggestions = (List<Map<String, Object>>) res.get("suggestions");
        assertEquals(2, suggestions.size(), "两批建议合并");
        assertEquals(CODE_A, byColumn(suggestions, "TEST_COL_A1").get("conceptCode"));
        assertEquals(CODE_B, byColumn(suggestions, "TEST_COL_B1").get("conceptCode"));
    }

    @Test
    void 全批失败_规则降级_无候选列照常记miss() {
        when(deepSeekClient.chat(eq("MAPPING_SUGGEST"), any(), any())).thenReturn(Optional.empty());
        when(deepSeekClient.model()).thenReturn("mock-model");
        Map<String, Object> res = mappingService.aiSuggest(DS, TABLE);

        assertEquals(Boolean.FALSE, res.get("llmUsed"), "全部批次失败不得虚报 llmUsed");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> suggestions = (List<Map<String, Object>>) res.get("suggestions");
        assertEquals(4, suggestions.size(), "降级规则建议逐列给出");
        // A1/B1 的注释含种子属性名（甲/乙字段编号）→ 规则 0.6 命中；A2/B2 零字面关联 → 零分占位
        assertEquals(0.6, ((Number) byColumn(suggestions, "TEST_COL_A1").get("confidence")).doubleValue());
        assertEquals(CODE_A, byColumn(suggestions, "TEST_COL_A1").get("conceptCode"));
        assertEquals(0.6, ((Number) byColumn(suggestions, "TEST_COL_B1").get("confidence")).doubleValue());
        assertEquals(0.0, ((Number) byColumn(suggestions, "TEST_COL_A2").get("confidence")).doubleValue(),
                "无字面关联列给零分占位");
        assertEquals(0.0, ((Number) byColumn(suggestions, "TEST_COL_B2").get("confidence")).doubleValue());
        long misses = missMapper.selectCount(new LambdaQueryWrapper<OntologyMiss>()
                .likeRight(OntologyMiss::getTerm, MISS_PREFIX)
                .eq(OntologyMiss::getSource, "MAPPING_AI"));
        assertEquals(2, misses, "仅无候选列记 ATTRIBUTE miss（增长回路不因降级断链，有候选列不误记）");
    }

    @Test
    void 部分批失败_失败批就地规则兜底_响应如实标注failedBatches() {
        // 第 1 批（A 列）LLM 失败、第 2 批（B 列）正常——第三态：既非全成功也非全失败
        when(deepSeekClient.chat(eq("MAPPING_SUGGEST"), any(), any())).thenAnswer(inv -> {
            String prompt = inv.getArgument(2);
            prompts.add(prompt == null ? "" : prompt);
            return prompt != null && prompt.contains(CODE_B)
                    ? Optional.of("[{\"column\":\"TEST_COL_B1\",\"conceptCode\":\"" + CODE_B + "\","
                            + "\"attrCode\":\"F1_B1\",\"confidence\":0.9,\"reason\":\"测试\"}]")
                    : Optional.empty();
        });
        when(deepSeekClient.model()).thenReturn("mock-model");
        Map<String, Object> res = mappingService.aiSuggest(DS, TABLE);

        assertEquals(Boolean.TRUE, res.get("llmUsed"), "乙批有产出，llmUsed=true");
        assertEquals(1, ((Number) res.get("failedBatches")).intValue(), "失败批如实计数");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> suggestions = (List<Map<String, Object>>) res.get("suggestions");
        assertEquals(3, suggestions.size(), "失败批规则兜底 2 条 + 成功批 LLM 1 条");
        assertEquals(0.6, ((Number) byColumn(suggestions, "TEST_COL_A1").get("confidence")).doubleValue(),
                "失败批的可规则命中列就地兜底（每列必有行不变式）");
        assertEquals(0.0, ((Number) byColumn(suggestions, "TEST_COL_A2").get("confidence")).doubleValue(),
                "失败批零字面关联列给零分占位");
        assertEquals(CODE_B, byColumn(suggestions, "TEST_COL_B1").get("conceptCode"),
                "成功批建议不受失败批拖累");
        long misses = missMapper.selectCount(new LambdaQueryWrapper<OntologyMiss>()
                .likeRight(OntologyMiss::getTerm, MISS_PREFIX)
                .eq(OntologyMiss::getSource, "MAPPING_AI"));
        assertEquals(2, misses, "仅真无候选列记 miss（A2/B2），失败批可规则命中列 A1 不被误记");
    }

    private static Map<String, Object> byColumn(List<Map<String, Object>> suggestions, String column) {
        return suggestions.stream().filter(s -> column.equals(s.get("column"))).findFirst().orElseThrow();
    }
}
