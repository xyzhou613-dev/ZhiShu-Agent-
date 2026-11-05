package com.bemodel.ontology;

import com.bemodel.ontology.entity.Attribute;
import com.bemodel.ontology.entity.Concept;
import com.bemodel.ontology.service.ConceptService;
import com.bemodel.ontology.service.OntologyCheckService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** D1 属性覆盖门禁：裸概念与缺生效映射属性的 WARN 缺陷确定性输出。 */
@SpringBootTest
class OntologyCoverageGateTest {

    private static final String CODE = "TEST_COVERAGE_GATE";

    @Autowired
    private ConceptService conceptService;
    @Autowired
    private OntologyCheckService checkService;
    @Autowired
    private com.bemodel.ontology.mapper.AttributeMapper attributeMapper;
    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        conceptService.lambdaUpdate().eq(Concept::getCode, CODE).remove();
        // 属性行也要清：残留属性会让下一次运行把"裸概念"误判为"有属性"
        attributeMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Attribute>()
                .eq(Attribute::getConceptCode, CODE));
    }

    @Test
    void 裸概念与缺映射属性各产一条WARN() {
        loginAs("ADMIN");
        Concept c = new Concept();
        c.setCode(CODE);
        c.setName("覆盖门禁测试概念");
        c.setDomainCode("OPS");
        conceptService.create(c);

        // 1) 无属性 → CONCEPT_NO_ATTR（先到 REVIEW 再发布，走真状态机）
        conceptService.transition(CODE, "REVIEW");
        conceptService.transition(CODE, "PUBLISHED");
        assertTrue(checkService.check().stream().anyMatch(d ->
                        "CONCEPT_NO_ATTR".equals(d.type()) && d.refs().contains(CODE)),
                "裸概念应产 CONCEPT_NO_ATTR");

        // 2) 有属性但无生效映射 → ATTR_MAPPING_COVERAGE
        Attribute a = new Attribute();
        a.setConceptCode(CODE);
        a.setAttrCode("test_attr_x");
        a.setAttrName("测试属性X");
        a.setDataType("STRING");
        attributeMapper.insert(a);
        assertTrue(checkService.check().stream().anyMatch(d ->
                "ATTR_MAPPING_COVERAGE".equals(d.type()) && d.refs().contains("test_attr_x")));
    }

    // ---------- helpers ----------

    private static void loginAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "tester", "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
