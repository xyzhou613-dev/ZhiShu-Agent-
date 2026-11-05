package com.bemodel.ontology.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bemodel.common.BizException;
import com.bemodel.ontology.entity.Relation;
import com.bemodel.ontology.mapper.RelationMapper;
import org.springframework.stereotype.Service;

/**
 * 概念关系管理：bm_relation 唯一键为 (from_concept, to_concept, relation_name)，
 * 公理字段（对称/传递/函数/逆函数/反对称/inverse_of/iri）随创建与更新全链路透出。
 */
@Service
public class RelationService extends ServiceImpl<RelationMapper, Relation> {

    public Relation create(Relation relation) {
        Long cnt = selectCountByUk(relation);
        if (cnt != null && cnt > 0) {
            throw new BizException("关系已存在: " + ukText(relation));
        }
        fillDefaults(relation);
        save(relation);
        return relation;
    }

    public Relation update(Relation relation) {
        if (relation.getId() == null || getById(relation.getId()) == null) {
            throw new BizException("关系不存在，无法更新: id=" + relation.getId());
        }
        fillDefaults(relation);
        updateById(relation);
        return relation;
    }

    public Relation getByUk(String fromConcept, String toConcept, String relationName) {
        return getOne(new LambdaQueryWrapper<Relation>()
                .eq(Relation::getFromConcept, fromConcept)
                .eq(Relation::getToConcept, toConcept)
                .eq(Relation::getRelationName, relationName)
                .last("LIMIT 1"), false);
    }

    private Long selectCountByUk(Relation r) {
        return baseMapper.selectCount(new LambdaQueryWrapper<Relation>()
                .eq(Relation::getFromConcept, r.getFromConcept())
                .eq(Relation::getToConcept, r.getToConcept())
                .eq(Relation::getRelationName, r.getRelationName()));
    }

    private void fillDefaults(Relation r) {
        if (r.getIsSymmetric() == null) r.setIsSymmetric(0);
        if (r.getIsTransitive() == null) r.setIsTransitive(0);
        if (r.getIsFunctional() == null) r.setIsFunctional(0);
        if (r.getIsInverseFunctional() == null) r.setIsInverseFunctional(0);
        if (r.getIsAsymmetric() == null) r.setIsAsymmetric(0);
    }

    private String ukText(Relation r) {
        return r.getFromConcept() + "->" + r.getToConcept() + "#" + r.getRelationName();
    }
}
