package com.bemodel.ontology.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bemodel.common.BizException;
import com.bemodel.modeling.entity.Axiom;
import com.bemodel.modeling.mapper.AxiomMapper;
import com.bemodel.ontology.entity.Attribute;
import com.bemodel.ontology.entity.Concept;
import com.bemodel.ontology.entity.ConceptParent;
import com.bemodel.ontology.entity.Domain;
import com.bemodel.ontology.entity.Relation;
import com.bemodel.ontology.mapper.AttributeMapper;
import com.bemodel.ontology.mapper.ConceptMapper;
import com.bemodel.ontology.mapper.ConceptParentMapper;
import com.bemodel.ontology.mapper.DomainMapper;
import com.bemodel.ontology.mapper.RelationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * OWL 导入管线（借鉴 Utopia：预览与落库同一计划）。
 * 解析 RDF/XML(.owl/.rdf) 与 Turtle(.ttl)：
 *   owl:Class/rdfs:Class → bm_concept（code=localName 转 UPPER_SNAKE，iri 原样保留）；
 *   owl:ObjectProperty   → bm_relation（domain/range→from/to，公理特性→结构公理列）；
 *   owl:DatatypeProperty → bm_attribute（range→data_type，无法映射降级 STRING）；
 *   rdfs:subClassOf      → bm_axiom（type='继承'，溯源）+ bm_concept_parent（结构化多父，第一个父为主父）。
 * 表达不了的 OWL 构造（disjointWith/equivalentClass/Restriction/匿名节点等）计入 unprojected，不报错：
 * “暂未投影”不是解析失败。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OwlImportService {

    /** OWL 导入的概念统一挂入该域 */
    private static final String IMPORT_DOMAIN = "IMPORT";

    private final ConceptMapper conceptMapper;
    private final RelationMapper relationMapper;
    private final AttributeMapper attributeMapper;
    private final AxiomMapper axiomMapper;
    private final DomainMapper domainMapper;
    private final ConceptParentMapper conceptParentMapper;

    public record ImportItem(String kind, String iri, String code, String name,
                             String disposition, String reason, Map<String, Object> detail) {
    }

    public record Plan(List<ImportItem> items, Map<String, Long> summary, long unprojected) {
    }

    // ---------- 预览 / 落库（同一代码路径：parse → plan） ----------

    public Map<String, Object> preview(MultipartFile file) {
        Plan plan = parse(file);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", plan.items());
        out.put("summary", plan.summary());
        out.put("unprojected", plan.unprojected());
        return out;
    }

    /** 落库：重新解析同一文件得到同一个计划再执行，保证预览=落库走同一代码路径 */
    @Transactional
    public Map<String, Object> execute(MultipartFile file) {
        Plan plan = parse(file);
        ensureImportDomain();
        long created = 0;
        long updated = 0;
        long skipped = 0;
        long degraded = 0;
        for (ImportItem item : plan.items()) {
            switch (item.disposition()) {
                case "KEY_TAKEN" -> skipped++;
                case "CREATE", "UPDATE", "DEGRADED_TO_STRING" -> {
                    apply(item, plan.items());
                    if ("CREATE".equals(item.disposition())) {
                        created++;
                    } else if ("UPDATE".equals(item.disposition())) {
                        updated++;
                    } else {
                        degraded++;
                    }
                }
                default -> throw new BizException("未知 disposition: " + item.disposition());
            }
        }
        // 第二遍落继承边：此时同计划内的父概念已全部落库（按 code 排序时子类可能先于父类 apply）
        for (ImportItem item : plan.items()) {
            if ("CLASS".equals(item.kind()) && !"KEY_TAKEN".equals(item.disposition())) {
                applyParentEdges(item);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", created);
        out.put("updated", updated);
        out.put("skipped", skipped);
        out.put("degraded", degraded);
        out.put("unprojected", plan.unprojected());
        return out;
    }

    // ---------- 解析 + 计划 ----------

    private Plan parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请上传非空的 OWL/Turtle 文件（表单字段名 file）");
        }
        Model model = ModelFactory.createDefaultModel();
        try {
            RDFParser.source(new ByteArrayInputStream(file.getBytes()))
                    .lang(langOf(file.getOriginalFilename()))
                    .parse(model.getGraph());
        } catch (Exception e) {
            throw new BizException("OWL 解析失败: " + e.getMessage());
        }

        long unprojected = 0;
        List<ImportItem> items = new ArrayList<>();

        // 类（owl:Class / rdfs:Class；匿名类与限制类暂未投影）
        TreeSet<Resource> classes = new TreeSet<>(Comparator.comparing(Resource::getURI));
        model.listResourcesWithProperty(RDF.type, OWL.Class).forEachRemaining(classes::add);
        model.listResourcesWithProperty(RDF.type, RDFS.Class).forEachRemaining(classes::add);
        for (Resource c : classes) {
            if (c.isAnon()) {
                unprojected++;
                continue;
            }
            if (isW3c(c)) {
                continue;
            }
            List<String> parents = new ArrayList<>();
            for (Statement st : c.listProperties(RDFS.subClassOf).toList()) {
                if (st.getObject().isResource() && !st.getObject().asResource().isAnon()
                        && !isW3c(st.getObject().asResource()) && !st.getObject().asResource().equals(c)) {
                    parents.add(upperSnake(st.getObject().asResource().getLocalName()));
                } else if (st.getObject().isResource() && st.getObject().asResource().isAnon()) {
                    unprojected++; // 限制类父级（someValuesFrom 等）暂未投影
                }
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("comment", comment(c));
            detail.put("subClassOf", parents);
            String code = codeOf(c);
            items.add(new ImportItem("CLASS", c.getURI(), code, label(c),
                    classDisposition(c.getURI(), code, detail), null, detail));
        }

        // 对象属性 → 关系（缺 domain/range 无法落 bm_relation 非空列，计入 unprojected）
        for (Resource p : namedOfType(model, OWL.ObjectProperty)) {
            if (isW3c(p)) {
                continue;
            }
            String domainLocal = namedRef(p, RDFS.domain);
            String rangeLocal = namedRef(p, RDFS.range);
            if (domainLocal == null || rangeLocal == null) {
                unprojected++;
                continue;
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("fromConcept", upperSnake(domainLocal));
            detail.put("toConcept", upperSnake(rangeLocal));
            detail.put("comment", comment(p));
            detail.put("isSymmetric", p.hasProperty(RDF.type, OWL.SymmetricProperty) ? 1 : 0);
            detail.put("isTransitive", p.hasProperty(RDF.type, OWL.TransitiveProperty) ? 1 : 0);
            detail.put("isFunctional", p.hasProperty(RDF.type, OWL.FunctionalProperty) ? 1 : 0);
            detail.put("isInverseFunctional", p.hasProperty(RDF.type, OWL.InverseFunctionalProperty) ? 1 : 0);
            detail.put("isAsymmetric", p.hasProperty(RDF.type, OWL2.AsymmetricProperty) ? 1 : 0);
            Statement inv = p.getProperty(OWL.inverseOf);
            if (inv != null && inv.getObject().isResource() && !inv.getObject().asResource().isAnon()) {
                detail.put("inverseOfIri", inv.getObject().asResource().getURI());
            }
            String name = label(p);
            items.add(new ImportItem("RELATION", p.getURI(), codeOf(p), name,
                    relationDisposition(p.getURI(), name,
                            str(detail.get("fromConcept")), str(detail.get("toConcept")), detail),
                    null, detail));
        }

        // 数据属性 → 概念属性（缺 domain 无法挂靠概念，计入 unprojected；无法映射的类型降级 STRING）
        for (Resource p : namedOfType(model, OWL.DatatypeProperty)) {
            if (isW3c(p)) {
                continue;
            }
            String domainLocal = namedRef(p, RDFS.domain);
            if (domainLocal == null) {
                unprojected++;
                continue;
            }
            String range = null;
            Statement rangeSt = p.getProperty(RDFS.range);
            if (rangeSt != null && rangeSt.getObject().isResource() && !rangeSt.getObject().asResource().isAnon()) {
                range = rangeSt.getObject().asResource().getURI();
            }
            String mapped = range == null ? null : mapDataType(range);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("conceptCode", upperSnake(domainLocal));
            detail.put("dataType", mapped == null ? "STRING" : mapped);
            detail.put("definition", comment(p));
            String disposition = attributeDisposition(str(detail.get("conceptCode")), lowerSnake(p.getLocalName()));
            if (range == null) {
                detail.put("degradeReason", "未声明 rdfs:range，按 STRING 落库");
                disposition = "DEGRADED_TO_STRING";
            } else if (mapped == null) {
                detail.put("originalRange", range);
                detail.put("degradeReason", "数据类型 " + localName(range) + " 无法映射，降级为 STRING");
                disposition = "DEGRADED_TO_STRING";
            }
            items.add(new ImportItem("ATTRIBUTE", p.getURI(), lowerSnake(p.getLocalName()), label(p),
                    disposition, null, detail));
        }

        // 暂未投影的 OWL 构造（不是解析失败，只是平台元模型尚无对应物）
        unprojected += model.listStatements(null, OWL.disjointWith, (RDFNode) null).toList().size();
        unprojected += model.listStatements(null, OWL.equivalentClass, (RDFNode) null).toList().size();
        unprojected += model.listStatements(null, OWL.propertyChainAxiom, (RDFNode) null).toList().size();
        unprojected += model.listResourcesWithProperty(RDF.type, OWL.Restriction).toList().size();
        unprojected += model.listResourcesWithProperty(RDF.type, OWL.NamedIndividual).toList().size();

        items.sort(Comparator.comparing(ImportItem::kind).thenComparing(ImportItem::code));
        Map<String, Long> summary = new LinkedHashMap<>();
        summary.put("create", items.stream().filter(i -> "CREATE".equals(i.disposition())).count());
        summary.put("update", items.stream().filter(i -> "UPDATE".equals(i.disposition())).count());
        summary.put("keyTaken", items.stream().filter(i -> "KEY_TAKEN".equals(i.disposition())).count());
        summary.put("degraded", items.stream().filter(i -> "DEGRADED_TO_STRING".equals(i.disposition())).count());
        return new Plan(items, summary, unprojected);
    }

    // ---------- disposition 判定 ----------

    /** IRI 命中→UPDATE；code 命中（IRI 空或一致）→UPDATE；code 被不同 IRI 占用→KEY_TAKEN；否则 CREATE */
    private String classDisposition(String iri, String code, Map<String, Object> detail) {
        Concept byIri = conceptMapper.selectOne(new LambdaQueryWrapper<Concept>()
                .eq(Concept::getIri, iri).last("LIMIT 1"));
        if (byIri != null) {
            detail.put("match", "IRI 命中已有概念 " + byIri.getCode());
            return "UPDATE";
        }
        Concept byCode = conceptMapper.selectOne(new LambdaQueryWrapper<Concept>()
                .eq(Concept::getCode, code).last("LIMIT 1"));
        if (byCode != null) {
            if (byCode.getIri() != null && !byCode.getIri().isBlank() && !byCode.getIri().equals(iri)) {
                detail.put("match", "code 被不同 IRI 的概念占用: " + byCode.getIri());
                return "KEY_TAKEN";
            }
            detail.put("match", "code 命中已有概念");
            return "UPDATE";
        }
        return "CREATE";
    }

    private String relationDisposition(String iri, String name, String fromConcept, String toConcept,
                                       Map<String, Object> detail) {
        Relation byIri = relationMapper.selectOne(new LambdaQueryWrapper<Relation>()
                .eq(Relation::getIri, iri).last("LIMIT 1"));
        if (byIri != null) {
            detail.put("match", "IRI 命中已有关系");
            return "UPDATE";
        }
        Relation byUk = relationMapper.selectOne(new LambdaQueryWrapper<Relation>()
                .eq(Relation::getFromConcept, fromConcept)
                .eq(Relation::getToConcept, toConcept)
                .eq(Relation::getRelationName, name).last("LIMIT 1"));
        if (byUk != null) {
            if (byUk.getIri() != null && !byUk.getIri().isBlank() && !byUk.getIri().equals(iri)) {
                detail.put("match", "关系唯一键被不同 IRI 占用: " + byUk.getIri());
                return "KEY_TAKEN";
            }
            detail.put("match", "关系唯一键 (from,to,name) 命中");
            return "UPDATE";
        }
        return "CREATE";
    }

    private String attributeDisposition(String conceptCode, String attrCode) {
        Attribute existing = attributeMapper.selectOne(new LambdaQueryWrapper<Attribute>()
                .eq(Attribute::getConceptCode, conceptCode)
                .eq(Attribute::getAttrCode, attrCode).last("LIMIT 1"));
        return existing == null ? "CREATE" : "UPDATE";
    }

    // ---------- 落库 ----------

    private void apply(ImportItem item, List<ImportItem> allItems) {
        switch (item.kind()) {
            case "CLASS" -> applyClass(item);
            case "RELATION" -> applyRelation(item, allItems);
            case "ATTRIBUTE" -> applyAttribute(item);
            default -> throw new BizException("未知导入项类型: " + item.kind());
        }
    }

    @SuppressWarnings("unchecked")
    private void applyClass(ImportItem item) {
        Map<String, Object> detail = item.detail();
        String comment = str(detail.get("comment"));
        Concept existing = conceptMapper.selectOne(new LambdaQueryWrapper<Concept>()
                .eq(Concept::getCode, item.code()).last("LIMIT 1"));
        if (existing == null) {
            Concept c = new Concept();
            c.setCode(item.code());
            c.setName(item.name());
            c.setDomainCode(IMPORT_DOMAIN);
            c.setDefinition(comment);
            c.setIri(item.iri());
            c.setStatus("DRAFT");
            c.setVersion(1);
            conceptMapper.insert(c);
        } else {
            existing.setName(item.name());
            if (comment != null && !comment.isBlank()) {
                existing.setDefinition(comment);
            }
            existing.setIri(item.iri());
            conceptMapper.updateById(existing);
        }
        // rdfs:subClassOf → bm_axiom（继承，溯源用）；结构化 bm_concept_parent 边在第二遍 applyParentEdges 落
        for (String parent : (List<String>) detail.get("subClassOf")) {
            String axiomCode = inheritAxiomCode(item.code(), parent);
            Long cnt = axiomMapper.selectCount(new LambdaQueryWrapper<Axiom>()
                    .eq(Axiom::getAxiomCode, axiomCode));
            if (cnt == null || cnt == 0) {
                Axiom axiom = new Axiom();
                axiom.setAxiomCode(axiomCode);
                axiom.setSubject(item.code());
                axiom.setPredicate("subClassOf");
                axiom.setObject(parent);
                axiom.setAxiomType("继承");
                axiom.setDescription("OWL 导入: " + item.iri());
                axiom.setStatus("PUBLISHED");
                axiomMapper.insert(axiom);
            }
        }
    }

    /** 继承边落库（execute 第二遍）：父概念须已存在；第一个父为主父；按 (child,parent) 判重幂等 */
    @SuppressWarnings("unchecked")
    private void applyParentEdges(ImportItem item) {
        int parentIdx = 0;
        for (String parent : (List<String>) item.detail().get("subClassOf")) {
            Long parentExists = conceptMapper.selectCount(new LambdaQueryWrapper<Concept>()
                    .eq(Concept::getCode, parent));
            Long pairExists = conceptParentMapper.selectCount(new LambdaQueryWrapper<ConceptParent>()
                    .eq(ConceptParent::getChildCode, item.code())
                    .eq(ConceptParent::getParentCode, parent));
            if (parentExists != null && parentExists > 0 && (pairExists == null || pairExists == 0)) {
                ConceptParent cp = new ConceptParent();
                cp.setChildCode(item.code());
                cp.setParentCode(parent);
                cp.setIsPrimary(parentIdx == 0 ? 1 : 0);
                conceptParentMapper.insert(cp);
            }
            parentIdx++;
        }
    }

    private void applyRelation(ImportItem item, List<ImportItem> allItems) {
        Map<String, Object> detail = item.detail();
        String from = str(detail.get("fromConcept"));
        String to = str(detail.get("toConcept"));
        Relation existing = relationMapper.selectOne(new LambdaQueryWrapper<Relation>()
                .eq(Relation::getFromConcept, from)
                .eq(Relation::getToConcept, to)
                .eq(Relation::getRelationName, item.name()).last("LIMIT 1"));
        Relation r = existing == null ? new Relation() : existing;
        r.setFromConcept(from);
        r.setToConcept(to);
        r.setRelationName(item.name());
        r.setDescription(str(detail.get("comment")));
        r.setIsSymmetric((Integer) detail.get("isSymmetric"));
        r.setIsTransitive((Integer) detail.get("isTransitive"));
        r.setIsFunctional((Integer) detail.get("isFunctional"));
        r.setIsInverseFunctional((Integer) detail.get("isInverseFunctional"));
        r.setIsAsymmetric((Integer) detail.get("isAsymmetric"));
        r.setIri(item.iri());
        String inverseOfIri = str(detail.get("inverseOfIri"));
        if (inverseOfIri != null) {
            r.setInverseOf(resolveRelationName(inverseOfIri, allItems));
        }
        if (existing == null) {
            relationMapper.insert(r);
        } else {
            relationMapper.updateById(r);
        }
    }

    /** owl:inverseOf IRI → 平台 relation_name：先看同计划内关系项，再看已有关系的 iri */
    private String resolveRelationName(String inverseOfIri, List<ImportItem> allItems) {
        for (ImportItem other : allItems) {
            if ("RELATION".equals(other.kind()) && inverseOfIri.equals(other.iri())) {
                return other.name();
            }
        }
        Relation existing = relationMapper.selectOne(new LambdaQueryWrapper<Relation>()
                .eq(Relation::getIri, inverseOfIri).last("LIMIT 1"));
        return existing == null ? null : existing.getRelationName();
    }

    private void applyAttribute(ImportItem item) {
        Map<String, Object> detail = item.detail();
        String conceptCode = str(detail.get("conceptCode"));
        Attribute existing = attributeMapper.selectOne(new LambdaQueryWrapper<Attribute>()
                .eq(Attribute::getConceptCode, conceptCode)
                .eq(Attribute::getAttrCode, item.code()).last("LIMIT 1"));
        Attribute a = existing == null ? new Attribute() : existing;
        a.setConceptCode(conceptCode);
        a.setAttrCode(item.code());
        a.setAttrName(item.name());
        a.setDataType(str(detail.get("dataType")));
        String definition = str(detail.get("definition"));
        String degradeReason = str(detail.get("degradeReason"));
        if (degradeReason != null) {
            definition = (definition == null ? "" : definition + "；") + "[" + degradeReason + "]";
        }
        a.setDefinition(definition);
        if (existing == null) {
            Integer maxSort = attributeMapper.selectList(new LambdaQueryWrapper<Attribute>()
                            .eq(Attribute::getConceptCode, conceptCode)
                            .orderByDesc(Attribute::getSort).last("LIMIT 1"))
                    .stream().findFirst().map(Attribute::getSort).orElse(0);
            a.setIsKey(0);
            a.setSort(maxSort + 1);
            attributeMapper.insert(a);
        } else {
            attributeMapper.updateById(a);
        }
    }

    private void ensureImportDomain() {
        Long cnt = domainMapper.selectCount(new LambdaQueryWrapper<Domain>()
                .eq(Domain::getCode, IMPORT_DOMAIN));
        if (cnt == null || cnt == 0) {
            Domain d = new Domain();
            d.setCode(IMPORT_DOMAIN);
            d.setName("OWL导入域");
            d.setDescription("外部 OWL 本体导入的概念默认落此域");
            d.setSort(99);
            domainMapper.insert(d);
        }
    }

    // ---------- 解析工具 ----------

    private List<Resource> namedOfType(Model model, Resource type) {
        TreeSet<Resource> set = new TreeSet<>(Comparator.comparing(Resource::getURI));
        model.listResourcesWithProperty(RDF.type, type).forEachRemaining(r -> {
            if (!r.isAnon() && r.getURI() != null) {
                set.add(r);
            }
        });
        return List.copyOf(set);
    }

    /** rdfs:domain/range 的第一个命名资源 localName（匿名/缺失 → null） */
    private String namedRef(Resource p, Property prop) {
        for (Statement st : p.listProperties(prop).toList()) {
            if (st.getObject().isResource() && !st.getObject().asResource().isAnon()
                    && !isW3c(st.getObject().asResource())) {
                return st.getObject().asResource().getLocalName();
            }
        }
        return null;
    }

    private boolean isW3c(Resource r) {
        String ns = r.getNameSpace();
        return ns == null || ns.startsWith("http://www.w3.org/");
    }

    /** rdfs:label：优先无语言标记或中文标记 */
    private String label(Resource r) {
        String fallback = r.getLocalName();
        for (Statement st : r.listProperties(RDFS.label).toList()) {
            if (st.getObject().isLiteral()) {
                String lang = st.getObject().asLiteral().getLanguage();
                if (lang == null || lang.isBlank() || lang.startsWith("zh")) {
                    return st.getObject().asLiteral().getString();
                }
                fallback = st.getObject().asLiteral().getString();
            }
        }
        return fallback;
    }

    private String comment(Resource r) {
        for (Statement st : r.listProperties(RDFS.comment).toList()) {
            if (st.getObject().isLiteral()) {
                return st.getObject().asLiteral().getString();
            }
        }
        return null;
    }

    private Lang langOf(String filename) {
        if (filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".ttl")) {
            return Lang.TURTLE;
        }
        return Lang.RDFXML;
    }

    // ---------- code / 数据类型 ----------

    /** 平台 code 风格：概念/关系 localName → UPPER_SNAKE（如 InpEncounter → INP_ENCOUNTER），≤64 */
    static String upperSnake(String localName) {
        return snake(localName, true);
    }

    /** 属性 code 风格：lower_snake（与既有 diag_code/visit_no 一致） */
    static String lowerSnake(String localName) {
        return snake(localName, false);
    }

    private static String snake(String localName, boolean upper) {
        if (localName == null) {
            localName = "";
        }
        String s = localName
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        s = upper ? s.toUpperCase(Locale.ROOT) : s.toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            s = "X";
        }
        if (Character.isDigit(s.charAt(0))) {
            s = (upper ? "C_" : "c_") + s;
        }
        return s.length() > 64 ? s.substring(0, 64) : s;
    }

    /** localName 生成 code（兜底：非 ASCII localName 清洗为空时用 IRI 哈希保证确定性） */
    private String codeOf(Resource r) {
        String code = upperSnake(r.getLocalName());
        if ("X".equals(code) && r.getLocalName() != null && !r.getLocalName().isEmpty()) {
            code = "OWL_" + "%08X".formatted(r.getURI().hashCode());
        }
        return code;
    }

    /** OWL 数据类型 → 平台 data_type（STRING/NUMBER/DATE/ENUM）；无法映射返回 null 走降级 */
    private String mapDataType(String rangeUri) {
        String t = localName(rangeUri);
        return switch (t) {
            case "string", "literal", "PlainLiteral" -> "STRING";
            case "integer", "int", "long", "short", "byte", "decimal", "double", "float",
                    "nonNegativeInteger", "nonPositiveInteger", "positiveInteger", "negativeInteger",
                    "unsignedInt", "unsignedLong", "unsignedShort", "unsignedByte" -> "NUMBER";
            case "date", "dateTime", "time" -> "DATE";
            case "boolean" -> "ENUM";
            default -> null;
        };
    }

    private String localName(String uri) {
        int i = Math.max(uri.lastIndexOf('#'), uri.lastIndexOf('/'));
        return i >= 0 ? uri.substring(i + 1) : uri;
    }

    private String inheritAxiomCode(String childCode, String parentCode) {
        return "AX-IMP-" + "%08X".formatted((childCode + "|" + parentCode).hashCode());
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
