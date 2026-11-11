package com.bemodel.compute;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ComputeNormGuardsTest {
    @Test void replacementCannotCrossRequestKindTimeOrFormCycle() {
        var at=Instant.parse("2026-11-11T00:00:00Z");
        var current=new ComputeNormGuards.DecisionRef(1,1,"SUBMISSION",at);
        assertDoesNotThrow(()->ComputeNormGuards.decisionReplacement(new ComputeNormGuards.DecisionRef(2,1,"SUBMISSION",at.plusSeconds(1)),current,Set.of(1L)));
        for (var next:List.of(new ComputeNormGuards.DecisionRef(2,2,"SUBMISSION",at.plusSeconds(1)),new ComputeNormGuards.DecisionRef(2,1,"CLOSURE",at.plusSeconds(1)),new ComputeNormGuards.DecisionRef(2,1,"SUBMISSION",at),new ComputeNormGuards.DecisionRef(1,1,"SUBMISSION",at.plusSeconds(1))))
            assertThrows(RuntimeException.class,()->ComputeNormGuards.decisionReplacement(next,current,Set.of(1L)));
        assertThrows(RuntimeException.class,()->ComputeNormGuards.decisionReplacement(new ComputeNormGuards.DecisionRef(2,1,"SUBMISSION",at.plusSeconds(1)),current,Set.of(2L)));
    }
    @Test void eventCannotClaimDifferentDecisionAndNeedsSource() {
        assertDoesNotThrow(()->ComputeNormGuards.eventSource("SIMULATOR",7L,7L));
        assertThrows(RuntimeException.class,()->ComputeNormGuards.eventSource(" ",null,null));
        assertThrows(RuntimeException.class,()->ComputeNormGuards.eventSource("SIMULATOR",7L,8L));
    }
    @Test void unusedCardsStayWithTheirGroupAndForeignOrDuplicateCardsFail() {
        assertEquals(List.of(3L,4L),ComputeNormGuards.reservedCards(List.of(1L,2L,3L,4L),List.of(1L,2L)));
        assertThrows(RuntimeException.class,()->ComputeNormGuards.reservedCards(List.of(1L,2L),List.of(1L,1L)));
        assertThrows(RuntimeException.class,()->ComputeNormGuards.reservedCards(List.of(1L,2L),List.of(3L)));
    }
}
