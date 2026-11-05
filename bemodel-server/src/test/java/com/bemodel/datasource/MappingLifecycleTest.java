package com.bemodel.datasource;

import com.bemodel.common.BizException;
import com.bemodel.datasource.entity.Mapping;
import com.bemodel.datasource.entity.MappingLog;
import com.bemodel.datasource.service.MappingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 映射生命周期（V30）端到端：AI→PROPOSED→ACTIVE→DEPRECATED，留痕 + 运行时只认 ACTIVE。 */
@SpringBootTest
class MappingLifecycleTest {

    private static final String DS = "DS_TEST";
    private static final String TABLE = "t_lifecycle_test";

    @Autowired
    private MappingService mappingService;

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        for (Mapping m : mappingService.list(DS, TABLE, null)) {
            mappingService.removeById(m.getId());
        }
    }

    @Test
    void ai来源落提议_人工来源直接生效() {
        loginAs("EDITOR");
        mappingService.saveBatch(List.of(
                mapping("ai_col", "AI"),
                mapping("manual_col", "MANUAL")));
        assertEquals("PROPOSED", byColumn("ai_col").getStatus(), "AI 来源应落 PROPOSED");
        assertEquals("ACTIVE", byColumn("manual_col").getStatus(), "MANUAL 来源应直接 ACTIVE");
    }

    @Test
    void 提议不进运行时_生效后可见() {
        loginAs("EDITOR");
        mappingService.saveBatch(List.of(mapping("gate_col", "AI")));
        Long id = byColumn("gate_col").getId();

        assertTrue(activeIds().noneMatch(id::equals), "PROPOSED 不应进 activeList");
        assertTrue(mappingService.list(DS, TABLE, "PROPOSED").stream().anyMatch(m -> m.getId().equals(id)),
                "管理列表可按 PROPOSED 筛出");

        mappingService.transition(id, "ACTIVE");
        assertTrue(activeIds().anyMatch(id::equals), "生效后进 activeList");
    }

    @Test
    void 流转留痕_操作人记录() {
        loginAs("EDITOR");
        mappingService.saveBatch(List.of(mapping("log_col", "AI")));
        Long id = byColumn("log_col").getId();
        mappingService.transition(id, "ACTIVE");
        mappingService.transition(id, "DEPRECATED");
        List<MappingLog> logs = mappingService.logOf(id);
        assertTrue(logs.size() >= 3, "CREATE+TRANSITION×2 至少三条: " + logs.size());
        assertTrue(logs.stream().anyMatch(l -> "TRANSITION".equals(l.getAction())
                && l.getOperator() != null && l.getBeforeJson() != null && l.getAfterJson() != null));
    }

    @Test
    void 无权角色不能流转() {
        loginAs("VIEWER");
        mappingService.saveBatch(List.of(mapping("perm_col", "AI")));
        Long id = byColumn("perm_col").getId();
        BizException ex = assertThrows(BizException.class,
                () -> mappingService.transition(id, "ACTIVE"));
        assertTrue(ex.getMessage().contains("EDITOR"), "实际: " + ex.getMessage());
    }

    @Test
    void 编辑留痕前后对照() {
        loginAs("EDITOR");
        mappingService.saveBatch(List.of(mapping("edit_col", "AI")));
        Long id = byColumn("edit_col").getId();
        Mapping patch = new Mapping();
        patch.setConceptCode("CHANGED_CONCEPT");
        Mapping updated = mappingService.updateMapping(id, patch);
        assertEquals("CHANGED_CONCEPT", updated.getConceptCode());
        assertTrue(mappingService.logOf(id).stream().anyMatch(l -> "UPDATE".equals(l.getAction())));
    }

    // ---------- helpers ----------

    private Mapping mapping(String column, String source) {
        Mapping m = new Mapping();
        m.setDsCode(DS);
        m.setTableName(TABLE);
        m.setColumnName(column);
        m.setConceptCode("PATIENT");
        m.setAttrCode("name");
        m.setSource(source);
        return m;
    }

    private Mapping byColumn(String column) {
        return mappingService.list(DS, TABLE, null).stream()
                .filter(m -> column.equals(m.getColumnName()))
                .findFirst().orElseThrow();
    }

    private java.util.stream.Stream<Long> activeIds() {
        return mappingService.activeList().stream().map(Mapping::getId);
    }

    private static void loginAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "tester", "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
