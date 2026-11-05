package com.bemodel.ontology.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bemodel.auth.CurrentUser;
import com.bemodel.common.BizException;
import com.bemodel.datasource.entity.Mapping;
import com.bemodel.datasource.mapper.MappingMapper;
import com.bemodel.ontology.entity.Attribute;
import com.bemodel.ontology.entity.Concept;
import com.bemodel.ontology.entity.ConceptParent;
import com.bemodel.ontology.entity.Domain;
import com.bemodel.ontology.entity.Relation;
import com.bemodel.ontology.entity.Term;
import com.bemodel.ontology.event.ConceptDeprecatedEvent;
import com.bemodel.ontology.mapper.AttributeMapper;
import com.bemodel.ontology.mapper.ConceptMapper;
import com.bemodel.ontology.mapper.ConceptParentMapper;
import com.bemodel.ontology.mapper.DomainMapper;
import com.bemodel.ontology.mapper.RelationMapper;
import com.bemodel.ontology.mapper.TermMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ConceptService extends ServiceImpl<ConceptMapper, Concept> {

    private final AttributeMapper attributeMapper;
    private final RelationMapper relationMapper;
    private final TermMapper termMapper;
    private final MappingMapper mappingMapper;
    private final ConceptParentMapper conceptParentMapper;
    private final DomainMapper domainMapper;
    private final ApplicationEventPublisher events;

    private static final Set<String> STATUS = Set.of("DRAFT", "REVIEW", "PUBLISHED", "DEPRECATED");
    // DRAFT/REVIEW→DEPRECATED 直达（维护治理加固）：撤销类操作不再被迫途经瞬态 PUBLISHED
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "DRAFT", Set.of("REVIEW", "DEPRECATED"),
            "REVIEW", Set.of("PUBLISHED", "DRAFT", "DEPRECATED"),
            "PUBLISHED", Set.of("DEPRECATED"),
            "DEPRECATED", Set.of("DRAFT")
    );

    public List<Concept> listByDomain(String domainCode) {
        return lambdaQuery()
                .eq(domainCode != null && !domainCode.isBlank(), Concept::getDomainCode, domainCode)
                .orderByAsc(Concept::getCode)
                .list();
    }

    public Concept getByCode(String code) {
        return lambdaQuery().eq(Concept::getCode, code).one();
    }

    public Map<String, Object> detail(String code) {
        Concept concept = getByCode(code);
        if (concept == null) {
            throw new BizException("概念不存在: " + code);
        }
        Map<String, Object> detail = new HashMap<>();
        detail.put("concept", concept);
        detail.put("attributes", attributeMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Attribute>()
                        .eq(Attribute::getConceptCode, code).orderByAsc(Attribute::getSort)));
        detail.put("relations", relationMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Relation>()
                        .eq(Relation::getFromConcept, code)
                        .or().eq(Relation::getToConcept, code)));
        detail.put("terms", termMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Term>()
                        .eq(Term::getConceptCode, code)));
        detail.put("parents", listParents(code));
        return detail;
    }

    // ---------- 多父继承 ----------

    public List<ConceptParent> listParents(String code) {
        return conceptParentMapper.selectList(new LambdaQueryWrapper<ConceptParent>()
                .eq(ConceptParent::getChildCode, code)
                .orderByDesc(ConceptParent::getIsPrimary).orderByAsc(ConceptParent::getParentCode));
    }

    /** 加父：父子都存在、不相同、不重复、不成环（child 已是 parent 的祖先则拒绝） */
    public ConceptParent addParent(String code, String parentCode, Integer isPrimary) {
        Concept child = getByCode(code);
        if (child == null) {
            throw new BizException("概念不存在: " + code);
        }
        if (parentCode == null || parentCode.isBlank()) {
            throw new BizException("父概念编码不能为空");
        }
        if (code.equals(parentCode)) {
            throw new BizException("概念不能继承自身: " + code);
        }
        if (getByCode(parentCode) == null) {
            throw new BizException("父概念不存在: " + parentCode);
        }
        if (conceptParentMapper.selectCount(new LambdaQueryWrapper<ConceptParent>()
                .eq(ConceptParent::getChildCode, code)
                .eq(ConceptParent::getParentCode, parentCode)) > 0) {
            throw new BizException("继承关系已存在: " + code + " → " + parentCode);
        }
        if (ancestorsOf(parentCode).contains(code)) {
            throw new BizException("不允许成环: " + parentCode + " 的祖先链上已存在 " + code);
        }
        ConceptParent cp = new ConceptParent();
        cp.setChildCode(code);
        cp.setParentCode(parentCode);
        cp.setIsPrimary(Integer.valueOf(1).equals(isPrimary) ? 1 : 0);
        if (cp.getIsPrimary() == 1) {
            clearPrimary(code);
        }
        conceptParentMapper.insert(cp);
        return cp;
    }

    public void removeParent(String code, String parentCode) {
        conceptParentMapper.delete(new LambdaQueryWrapper<ConceptParent>()
                .eq(ConceptParent::getChildCode, code)
                .eq(ConceptParent::getParentCode, parentCode));
    }

    /** 设主父：同 child 唯一主父 */
    public ConceptParent setPrimaryParent(String code, String parentCode) {
        ConceptParent cp = conceptParentMapper.selectOne(new LambdaQueryWrapper<ConceptParent>()
                .eq(ConceptParent::getChildCode, code)
                .eq(ConceptParent::getParentCode, parentCode).last("LIMIT 1"));
        if (cp == null) {
            throw new BizException("继承关系不存在: " + code + " → " + parentCode);
        }
        clearPrimary(code);
        cp.setIsPrimary(1);
        conceptParentMapper.updateById(cp);
        return cp;
    }

    private void clearPrimary(String childCode) {
        List<ConceptParent> primaries = conceptParentMapper.selectList(new LambdaQueryWrapper<ConceptParent>()
                .eq(ConceptParent::getChildCode, childCode).eq(ConceptParent::getIsPrimary, 1));
        for (ConceptParent p : primaries) {
            p.setIsPrimary(0);
            conceptParentMapper.updateById(p);
        }
    }

    /** 全部祖先（沿 parent 链向上 BFS；带 visited 防既有数据成环时死循环） */
    public java.util.Set<String> ancestorsOf(String code) {
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        java.util.Deque<String> queue = new java.util.ArrayDeque<>();
        queue.add(code);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (ConceptParent p : conceptParentMapper.selectList(new LambdaQueryWrapper<ConceptParent>()
                    .eq(ConceptParent::getChildCode, cur))) {
                if (seen.add(p.getParentCode())) {
                    queue.add(p.getParentCode());
                }
            }
        }
        seen.remove(code);
        return seen;
    }

    public Concept create(Concept concept) {
        if (getByCode(concept.getCode()) != null) {
            throw new BizException("概念编码已存在: " + concept.getCode());
        }
        concept.setStatus("DRAFT");
        concept.setVersion(1);
        save(concept);
        return concept;
    }

    /**
     * 内容编辑（字段白名单，维护治理加固 H1）：code/status/version/owner 不可经编辑通道改写——
     * 状态只能走 transition（评审门禁+防自审在那条路上），版本只随发布递增，负责人变更是独立动作。
     * 已发布概念允许就地修订内容（口径随业务演进的真实场景，V23 结算概念修订为 SQL 直改的先例），
     * 发布快照在下一次发布时收编；变更留痕属提案引擎线（spec §7），本步不落日志表。
     *
     * 写法是「只写白名单列」的 LambdaUpdateWrapper，而不是读出的全量实体回写：
     * 全量回写会把读快照里的 status/version/owner 原样写回，并发 transition 的废弃/发布
     * 会被一次内容编辑无痕回滚；回写也压制 updated_at 的 DDL ON UPDATE CURRENT_TIMESTAMP。
     * iri 允许显式置 null（清空语义）——set() 直写 NULL，不受 MyBatis-Plus 默认
     * NOT_NULL 更新策略（跳过 null 字段）影响。
     */
    public Concept updateContent(Concept patch) {
        if (patch.getId() == null) {
            throw new BizException("缺少概念 id，无法更新");
        }
        Concept existing = getById(patch.getId());
        if (existing == null) {
            throw new BizException("概念不存在: id=" + patch.getId());
        }
        String name = existing.getName();
        String definition = existing.getDefinition();
        String iri = existing.getIri();
        String domainCode = existing.getDomainCode();
        if (patch.getName() != null) {
            if (patch.getName().isBlank()) {
                throw new BizException("概念名称不能为空");
            }
            name = patch.getName().trim();
        }
        if (patch.getDefinition() != null) {
            definition = patch.getDefinition();
        }
        if (patch.getIri() != null) {
            iri = patch.getIri().isBlank() ? null : patch.getIri().trim();
        }
        if (patch.getDomainCode() != null) {
            String d = patch.getDomainCode().trim();
            Long domainCnt = domainMapper.selectCount(
                    new LambdaQueryWrapper<Domain>().eq(Domain::getCode, d));
            if (domainCnt == null || domainCnt == 0) {
                throw new BizException("业务域不存在: " + d);
            }
            domainCode = d;
        }
        boolean updated = lambdaUpdate()
                .eq(Concept::getId, patch.getId())
                .set(Concept::getName, name)
                .set(Concept::getDefinition, definition)
                .set(Concept::getIri, iri)
                .set(Concept::getDomainCode, domainCode)
                .update();
        if (!updated) {
            throw new BizException("概念不存在或已被删除: id=" + patch.getId());
        }
        return getById(patch.getId());
    }

    /** 仅草稿可删除；存在属性/关系/术语/字段映射任一引用时拒绝并列出数量 */
    public void deleteDraft(String code) {
        Concept concept = getByCode(code);
        if (concept == null) {
            throw new BizException("概念不存在: " + code);
        }
        if (!"DRAFT".equals(concept.getStatus())) {
            throw new BizException("仅草稿(DRAFT)状态可删除，当前状态: " + concept.getStatus());
        }
        long attrs = attributeMapper.selectCount(
                new LambdaQueryWrapper<Attribute>().eq(Attribute::getConceptCode, code));
        long rels = relationMapper.selectCount(new LambdaQueryWrapper<Relation>()
                .eq(Relation::getFromConcept, code).or().eq(Relation::getToConcept, code));
        long terms = termMapper.selectCount(
                new LambdaQueryWrapper<Term>().eq(Term::getConceptCode, code));
        long mappings = mappingMapper.selectCount(
                new LambdaQueryWrapper<Mapping>().eq(Mapping::getConceptCode, code));
        long parents = conceptParentMapper.selectCount(new LambdaQueryWrapper<ConceptParent>()
                .eq(ConceptParent::getChildCode, code).or().eq(ConceptParent::getParentCode, code));
        if (attrs + rels + terms + mappings + parents > 0) {
            throw new BizException(String.format(
                    "概念存在引用，拒绝删除: 属性%d个、关系%d条、术语%d条、字段映射%d条、继承边%d条",
                    attrs, rels, terms, mappings, parents));
        }
        removeById(concept.getId());
    }

    public Concept transition(String code, String target) {
        Concept concept = getByCode(code);
        if (concept == null) {
            throw new BizException("概念不存在: " + code);
        }
        if (!STATUS.contains(target)) {
            throw new BizException("非法状态: " + target);
        }
        Set<String> allowed = TRANSITIONS.getOrDefault(concept.getStatus(), Set.of());
        if (!allowed.contains(target)) {
            throw new BizException("不允许从 " + concept.getStatus() + " 流转到 " + target);
        }
        // 评审门禁（P0②）：发布须评审员或管理员，且评审员不得发布本人负责的概念（防自审自发）。
        // 废弃分层（维护治理加固 H2）：已发布概念的废弃是评审决定（下游有 ACTIVE 映射与快照消费），
        // 维持评审员门禁；未发布概念（DRAFT/REVIEW）的废弃不设门禁——草稿从未对外发布，废弃即撤销，
        // 建模员即可（URL 层 /api/concept/transition 已限 ADMIN/EDITOR/REVIEWER）。
        // 不为废弃加防自审：发布是信任注入须防自审，废弃是内容移除，负责人下架自己的概念是合法动作。
        if ("PUBLISHED".equals(target)) {
            if (!CurrentUser.hasAnyRole("REVIEWER", "ADMIN")) {
                throw new BizException("概念发布需评审员（REVIEWER）或管理员（ADMIN）审批: " + code);
            }
            if (!CurrentUser.hasAnyRole("ADMIN")
                    && concept.getOwner() != null
                    && concept.getOwner().equalsIgnoreCase(CurrentUser.username())) {
                throw new BizException("评审员不得发布本人负责的概念（" + code + "，负责人=" + concept.getOwner()
                        + "），请交由其他评审员评审");
            }
        } else if ("DEPRECATED".equals(target) && "PUBLISHED".equals(concept.getStatus())) {
            if (!CurrentUser.hasAnyRole("REVIEWER", "ADMIN")) {
                throw new BizException("废弃已发布概念需评审员（REVIEWER）或管理员（ADMIN）审批: " + code);
            }
        }
        concept.setStatus(target);
        if ("PUBLISHED".equals(target)) {
            concept.setVersion(concept.getVersion() + 1);
        }
        updateById(concept);
        if ("DEPRECATED".equals(target)) {
            // 废弃联动（维护治理加固 H3，进程内同步事件）：消费者自查下游——ACTIVE 映射告警、
            // 搜索/投影隔离在各监听器；监听器失败不得回滚废弃主操作
            events.publishEvent(new ConceptDeprecatedEvent(code, concept.getName()));
        }
        return concept;
    }
}
