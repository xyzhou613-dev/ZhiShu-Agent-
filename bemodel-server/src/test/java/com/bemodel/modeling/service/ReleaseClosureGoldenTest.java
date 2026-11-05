package com.bemodel.modeling.service;

import com.bemodel.ontology.entity.Relation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 闭包 BFS golden（纯逻辑零 Spring，spec §8）：直链/菱形/截断三夹具——补 rebuildRelationClosures 抽取前的零覆盖缺口 */
class ReleaseClosureGoldenTest {

    private static Relation rel(String from, String to, String name, int transitive) {
        Relation r = new Relation();
        r.setFromConcept(from);
        r.setToConcept(to);
        r.setRelationName(name);
        r.setIsTransitive(transitive);
        return r;
    }

    @Test
    void chainDepthsGrowOnePerHop() {
        ReleaseService.ClosureDepth cd = ReleaseService.closureBfs(List.of(
                rel("A", "B", "属于", 1), rel("B", "C", "属于", 1), rel("C", "D", "属于", 1)), 12);
        Map<String, Map<String, Integer>> bySource = cd.depths().get("属于");
        assertEquals(Map.of("B", 1, "C", 2, "D", 3), bySource.get("A"));
        assertEquals(Map.of("C", 1, "D", 2), bySource.get("B"));
        assertEquals(Map.of("D", 1), bySource.get("C"));
        assertTrue(cd.cappedNames().isEmpty());
    }

    @Test
    void diamondKeepsShortestDepthOnce() {
        ReleaseService.ClosureDepth cd = ReleaseService.closureBfs(List.of(
                rel("A", "B", "属于", 1), rel("A", "C", "属于", 1),
                rel("B", "D", "属于", 1), rel("C", "D", "属于", 1)), 12);
        assertEquals(Map.of("B", 1, "C", 1, "D", 2), cd.depths().get("属于").get("A"));
    }

    @Test
    void depthCapTruncatesAndFlagsCapped() {
        ReleaseService.ClosureDepth cd = ReleaseService.closureBfs(List.of(
                rel("A", "B", "属于", 1), rel("B", "C", "属于", 1), rel("C", "D", "属于", 1)), 2);
        assertEquals(Map.of("B", 1, "C", 2), cd.depths().get("属于").get("A"));
        assertTrue(cd.cappedNames().contains("属于"));
    }

    @Test
    void selfLoopAndNonTransitiveExcluded() {
        ReleaseService.ClosureDepth cd = ReleaseService.closureBfs(List.of(
                rel("A", "A", "属于", 1), rel("A", "B", "属于", 1), rel("B", "C", "其他", 0)), 12);
        assertEquals(Map.of("B", 1), cd.depths().get("属于").get("A"));
        assertFalse(cd.depths().containsKey("其他"));
    }
}
