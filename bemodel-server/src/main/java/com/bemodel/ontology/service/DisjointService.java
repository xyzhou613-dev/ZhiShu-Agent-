package com.bemodel.ontology.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bemodel.common.BizException;
import com.bemodel.ontology.entity.Concept;
import com.bemodel.ontology.entity.Disjoint;
import com.bemodel.ontology.mapper.ConceptMapper;
import com.bemodel.ontology.mapper.DisjointMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 概念互斥公理管理。互斥是对称语义：入库按 code 字典序规范化为单行（a < b），
 * 判定/查找一律 A-B、B-A 双向查询，不产生冗余双行。
 */
@Service
@RequiredArgsConstructor
public class DisjointService extends ServiceImpl<DisjointMapper, Disjoint> {

    private final ConceptMapper conceptMapper;

    public List<Disjoint> listPairs() {
        return list(new LambdaQueryWrapper<Disjoint>()
                .orderByAsc(Disjoint::getConceptACode).orderByAsc(Disjoint::getConceptBCode));
    }

    /** 双向判定：两概念是否已声明互斥 */
    public boolean isDisjoint(String conceptACode, String conceptBCode) {
        return findPair(conceptACode, conceptBCode) != null;
    }

    /** 双向查找互斥行（命中即返回，不论存储方向） */
    public Disjoint findPair(String conceptACode, String conceptBCode) {
        return getOne(new LambdaQueryWrapper<Disjoint>()
                .and(w -> w.eq(Disjoint::getConceptACode, conceptACode).eq(Disjoint::getConceptBCode, conceptBCode))
                .or(w -> w.eq(Disjoint::getConceptACode, conceptBCode).eq(Disjoint::getConceptBCode, conceptACode))
                .last("LIMIT 1"), false);
    }

    public Disjoint create(String conceptACode, String conceptBCode, String definition) {
        if (conceptACode == null || conceptACode.isBlank() || conceptBCode == null || conceptBCode.isBlank()) {
            throw new BizException("互斥两端的概念编码不能为空");
        }
        if (conceptACode.equals(conceptBCode)) {
            throw new BizException("互斥两端不能是同一概念: " + conceptACode);
        }
        requireConcept(conceptACode);
        requireConcept(conceptBCode);
        if (findPair(conceptACode, conceptBCode) != null) {
            throw new BizException("互斥对已存在: " + conceptACode + " ✕ " + conceptBCode);
        }
        Disjoint d = new Disjoint();
        // 规范化存储方向，保证 (A,B) 与 (B,A) 只落一行
        d.setConceptACode(conceptACode.compareTo(conceptBCode) < 0 ? conceptACode : conceptBCode);
        d.setConceptBCode(conceptACode.compareTo(conceptBCode) < 0 ? conceptBCode : conceptACode);
        d.setDefinition(definition);
        d.setStatus("PUBLISHED");
        save(d);
        return d;
    }

    /** 公理自由文本 → 概念 code：剥离（…）限定语后按 code 精确匹配，再按概念名称匹配；解析不出返回 null */
    public String resolveConceptCode(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = text.replaceAll("（.*?）", "").trim();
        Concept c = conceptMapper.selectOne(new LambdaQueryWrapper<Concept>()
                .eq(Concept::getCode, cleaned).or().eq(Concept::getName, cleaned)
                .last("LIMIT 1"), false);
        return c == null ? null : c.getCode();
    }

    private void requireConcept(String code) {
        Long cnt = conceptMapper.selectCount(
                new LambdaQueryWrapper<Concept>().eq(Concept::getCode, code));
        if (cnt == null || cnt == 0) {
            throw new BizException("概念不存在: " + code);
        }
    }
}
