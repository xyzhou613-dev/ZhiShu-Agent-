package com.bemodel.compute;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** The eight v0.7 formulas, shared by queries, rule checks and future Agent tools. Bytes throughout. */
public final class ComputeResourceMath {
    private ComputeResourceMath() {}
    public static final String VERSION=ComputeVersions.FORMULAS;
    public record Bytes(long memoryBytes,long temporaryBytes,long persistentBytes) {
        public Bytes { if(memoryBytes<0||temporaryBytes<0||persistentBytes<0) throw new IllegalArgumentException("资源字节数不能为负"); }
        public static Bytes zero(){return new Bytes(0,0,0);}
        public Bytes plus(Bytes b){return new Bytes(Math.addExact(memoryBytes,b.memoryBytes),Math.addExact(temporaryBytes,b.temporaryBytes),Math.addExact(persistentBytes,b.persistentBytes));}
        public boolean within(Bytes cap){return memoryBytes<=cap.memoryBytes&&temporaryBytes<=cap.temporaryBytes&&persistentBytes<=cap.persistentBytes;}
        public Map<String,Long> values(){return Map.of("memoryBytes",memoryBytes,"temporaryBytes",temporaryBytes,"persistentBytes",persistentBytes);}
    }
    public record QueueEntry(long requestId,long priority,Instant submittedAt) {
        public QueueEntry {if(requestId<=0||priority<0||submittedAt==null) throw new IllegalArgumentException("队列字段不完整");}
    }
    public static boolean groupAvailable(boolean hasOpenHold,boolean hasPendingAllocation){return !hasOpenHold&&!hasPendingAllocation;}
    public static Bytes projection(Bytes continuingDeployment,Bytes effectiveReservation,Bytes newReservation){return continuingDeployment.plus(effectiveReservation).plus(newReservation);}
    public static Bytes restoreProjection(Bytes otherDeployment,Bytes otherReservation,Bytes restoreDeployment){return otherDeployment.plus(otherReservation).plus(restoreDeployment);}
    public static boolean preemptible(String holderKind,String taskType,String group,long groupCards,boolean registered,long requestPriority,long deploymentPriority){
        if(requestPriority<0||deploymentPriority<0||groupCards<=0) throw new IllegalArgumentException("抢占字段非法");
        return "DEPLOYMENT".equals(holderKind)&&"TRAIN".equals(taskType)&&registered&&Set.of("A","B").contains(group)&&groupCards>1&&requestPriority>deploymentPriority;
    }
    public static <T> List<T> reservedCards(List<T> group,List<T> used){return ComputeNormGuards.reservedCards(group,used);}
    public static boolean evidenceValid(Instant observedAt,Instant validUntil,Instant asOf,long maxAgeSeconds){
        if(observedAt==null||validUntil==null||asOf==null||maxAgeSeconds<=0) return false;
        return !observedAt.isAfter(asOf)&&!validUntil.isBefore(asOf)&&Duration.between(observedAt,asOf).compareTo(Duration.ofSeconds(maxAgeSeconds))<=0;
    }
    public static List<Long> queueOrder(List<QueueEntry> entries){
        if(entries==null||entries.stream().map(QueueEntry::requestId).distinct().count()!=entries.size()) throw new IllegalArgumentException("队列编号重复或缺失");
        return entries.stream().sorted(Comparator.comparingLong(QueueEntry::priority).reversed().thenComparing(QueueEntry::submittedAt).thenComparingLong(QueueEntry::requestId)).map(QueueEntry::requestId).toList();
    }
    public static boolean qualifiedHandoff(boolean targetMatches,boolean isHead,boolean capacityPass,boolean resourcesPass,boolean healthPass){return targetMatches&&isHead&&capacityPass&&resourcesPass&&healthPass;}
}
