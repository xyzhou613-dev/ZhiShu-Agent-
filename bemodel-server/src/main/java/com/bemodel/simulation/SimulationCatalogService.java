package com.bemodel.simulation;

import com.bemodel.datasource.entity.Mapping;
import com.bemodel.datasource.mapper.MappingMapper;
import com.bemodel.lab.LabProps;
import com.bemodel.ontology.entity.Attribute;
import com.bemodel.ontology.entity.Concept;
import com.bemodel.ontology.entity.Relation;
import com.bemodel.ontology.mapper.AttributeMapper;
import com.bemodel.ontology.mapper.ConceptMapper;
import com.bemodel.ontology.mapper.RelationMapper;
import com.bemodel.simulation.engine.CatalogSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 传导目录快照:bm_relation/bm_mapping(ACTIVE)/bm_attribute/bm_concept 一次读入。
 * 映射列只保留实际克隆进演示沙箱的表——如 opd_presc 有 MEDICAL_ORDER 映射但不在沙箱,
 * 不参与传导(主表选择按"该表本概念列数"自然胜出,见引擎)。
 */
@Service
public class SimulationCatalogService {

    private final RelationMapper relationMapper;
    private final MappingMapper mappingMapper;
    private final AttributeMapper attributeMapper;
    private final ConceptMapper conceptMapper;
    private final JdbcTemplate platform;
    private final LabProps labProps;

    public SimulationCatalogService(RelationMapper relationMapper, MappingMapper mappingMapper,
                                    AttributeMapper attributeMapper, ConceptMapper conceptMapper,
                                    JdbcTemplate platform, LabProps labProps) {
        this.relationMapper = relationMapper;
        this.mappingMapper = mappingMapper;
        this.attributeMapper = attributeMapper;
        this.conceptMapper = conceptMapper;
        this.platform = platform;
        this.labProps = labProps;
    }

    public CatalogSnapshot snapshot() {
        Set<String> sandboxTables = platform.queryForList(
                        "SELECT table_name FROM information_schema.tables WHERE table_schema = ?",
                        String.class, labProps.getDbName()).stream()
                .map(t -> t.toLowerCase()).collect(Collectors.toSet());

        List<CatalogSnapshot.Rel> rels = relationMapper.selectList(null).stream()
                .map(r -> new CatalogSnapshot.Rel(r.getFromConcept(), r.getToConcept(), r.getRelationName()))
                .toList();
        List<CatalogSnapshot.Col> cols = mappingMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Mapping>()
                                .eq(Mapping::getStatus, "ACTIVE")).stream()
                .filter(m -> m.getTableName() != null && sandboxTables.contains(m.getTableName().toLowerCase()))
                .map(m -> new CatalogSnapshot.Col(m.getTableName(), m.getColumnName(),
                        m.getConceptCode(), m.getAttrCode()))
                .toList();
        Map<String, String> attrNames = new LinkedHashMap<>();
        for (Attribute a : attributeMapper.selectList(null)) {
            attrNames.put(a.getConceptCode() + "." + a.getAttrCode(), a.getAttrName());
        }
        Map<String, String> conceptNames = new LinkedHashMap<>();
        for (Concept c : conceptMapper.selectList(null)) {
            conceptNames.put(c.getCode(), c.getName());
        }
        return new CatalogSnapshot(rels, cols, attrNames, conceptNames);
    }
}
