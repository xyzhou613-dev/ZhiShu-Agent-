package com.bemodel.compute;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static com.bemodel.compute.ComputeResourceMath.*;
import static org.junit.jupiter.api.Assertions.*;
class ComputeResourceMathTest {
    @Test void availabilityRequiresBothNoHoldAndNoReservation(){assertTrue(groupAvailable(false,false));assertFalse(groupAvailable(true,false));assertFalse(groupAvailable(false,true));}
    @Test void projectionUsesThreeIndependentComponentsAndRejectsOverflow(){assertEquals(new Bytes(17,28,39),projection(new Bytes(10,20,30),new Bytes(5,6,7),new Bytes(2,2,2)));assertThrows(ArithmeticException.class,()->projection(new Bytes(Long.MAX_VALUE,0,0),new Bytes(1,0,0),Bytes.zero()));assertThrows(IllegalArgumentException.class,()->new Bytes(-1,0,0));}
    @Test void restorationCountsItsBaselineOnce(){assertEquals(new Bytes(60,12,9),restoreProjection(new Bytes(20,4,3),new Bytes(10,2,1),new Bytes(30,6,5)));assertFalse(new Bytes(61,1,1).within(new Bytes(60,100,100)));}
    @Test void singleCardCEqualPriorityAndJobsCannotBePreempted(){assertTrue(preemptible("DEPLOYMENT","TRAIN","B",2,true,2,1));assertFalse(preemptible("DEPLOYMENT","TRAIN","C",1,true,2,1));assertFalse(preemptible("DEPLOYMENT","TRAIN","B",2,true,1,1));assertFalse(preemptible("ALLOCATION","TRAIN","B",2,true,3,1));}
    @Test void retainedCardsAreASetDifferenceAndRemainReserved(){assertEquals(List.of("g3","g4"),reservedCards(List.of("g1","g2","g3","g4"),List.of("g1","g2")));assertThrows(RuntimeException.class,()->reservedCards(List.of("g1"),List.of("g2")));}
    @Test void evidenceChecksBothValidityAndAgeWithInclusiveEdges(){var at=Instant.parse("2026-11-11T00:00:00Z");assertTrue(evidenceValid(at,at.plusSeconds(60),at.plusSeconds(30),30));assertFalse(evidenceValid(at,at.plusSeconds(60),at.plusSeconds(31),30));assertFalse(evidenceValid(at,at.plusSeconds(29),at.plusSeconds(30),30));assertFalse(evidenceValid(at,at.plusSeconds(60),at.minusNanos(1),30));assertFalse(evidenceValid(null,at,at,30));}
    @Test void equalPriorityAndTimestampUseRequestId(){var at=Instant.parse("2026-11-11T00:00:00Z");assertEquals(List.of(1L,3L,2L),queueOrder(List.of(new QueueEntry(3,2,at),new QueueEntry(2,1,at.minusSeconds(1)),new QueueEntry(1,2,at))));assertThrows(IllegalArgumentException.class,()->queueOrder(List.of(new QueueEntry(1,2,at),new QueueEntry(1,2,at))));}
    @Test void handoffRequiresEveryRecheckedCondition(){assertTrue(qualifiedHandoff(true,true,true,true,true));assertFalse(qualifiedHandoff(true,true,false,true,true));assertFalse(qualifiedHandoff(false,true,true,true,true));assertFalse(qualifiedHandoff(true,false,true,true,true));assertFalse(qualifiedHandoff(true,true,true,false,true));assertFalse(qualifiedHandoff(true,true,true,true,false));}
}
