package com.bemodel.auth;

import com.bemodel.common.BizException;
import com.bemodel.modeling.entity.Release;
import com.bemodel.modeling.service.ReleaseService;
import com.bemodel.ontology.entity.Concept;
import com.bemodel.ontology.service.ConceptService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 评审门禁（P0②）端到端：概念发布/废弃须 REVIEWER/ADMIN；评审员不得发布本人负责的概念（防自审自发）；
 * 本体版本发布须 REVIEWER/ADMIN 且落审批留痕（approvedBy/approvedAt，V29）。
 * URL 层放行矩阵见 SecurityConfig（REVIEWER 只进评审动作与只读端点），服务层 CurrentUser 兜底。
 */
@SpringBootTest
class ReviewerRoleTest {

    @Autowired
    private ConceptService conceptService;
    @Autowired
    private ReleaseService releaseService;

    private static final String CODE = "TEST_REVIEW_GATE";

    @AfterEach
    void cleanupAndClear() {
        SecurityContextHolder.clearContext();
        conceptService.lambdaUpdate().eq(Concept::getCode, CODE).remove();
    }

    @Test
    void editorBlockedFromPublishing() {
        conceptService.create(draft());
        conceptService.transition(CODE, "REVIEW"); // REVIEW 流转不设门禁
        loginAs("EDITOR");
        BizException ex = assertThrows(BizException.class,
                () -> conceptService.transition(CODE, "PUBLISHED"));
        assertTrue(ex.getMessage().contains("评审员（REVIEWER）或管理员（ADMIN）"),
                "实际消息: " + ex.getMessage());
    }

    @Test
    void reviewerCanPublishOtherOwnersConcept() {
        conceptService.create(draft());
        conceptService.transition(CODE, "REVIEW");
        loginAs("REVIEWER");
        Concept published = conceptService.transition(CODE, "PUBLISHED");
        assertEquals("PUBLISHED", published.getStatus());
        assertEquals(2, published.getVersion(), "发布后版本号应+1");
    }

    @Test
    void selfReviewBlockedAdminExempt() {
        Concept d = draft();
        d.setOwner("tester"); // 负责人=当前评审员 tester，构成自审自发
        conceptService.create(d);
        conceptService.transition(CODE, "REVIEW");
        loginAs("REVIEWER");
        BizException ex = assertThrows(BizException.class,
                () -> conceptService.transition(CODE, "PUBLISHED"));
        assertTrue(ex.getMessage().contains("不得发布本人负责的概念"), "实际消息: " + ex.getMessage());
        // ADMIN 豁免 owner 检查（管理员可代发布，审批留痕在 approvedBy）
        loginAs("ADMIN");
        Concept published = conceptService.transition(CODE, "PUBLISHED");
        assertEquals("PUBLISHED", published.getStatus());
    }

    @Test
    void releaseApprovalRecordedForReviewer() {
        loginAs("REVIEWER");
        Release release = releaseService.publish("评审员审批发布测试", "modeler", true);
        assertEquals("tester", release.getApprovedBy(), "审批人应记录为当前主体");
        assertNotNull(release.getApprovedAt());
        releaseService.removeById(release.getId());
    }

    @Test
    void releaseBlockedForEditor() {
        loginAs("EDITOR");
        assertThrows(BizException.class, () -> releaseService.publish("编辑审批发布测试", "modeler"));
    }

    // ---------- helpers ----------

    private Concept draft() {
        Concept c = new Concept();
        c.setCode(CODE);
        c.setName("评审门禁测试概念");
        c.setDomainCode("OPS");
        return c;
    }

    private static void loginAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "tester", "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
