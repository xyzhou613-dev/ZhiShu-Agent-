package com.bemodel.compute;

import com.bemodel.common.BizException;
import java.time.Instant;
import java.util.*;

/** Shared write guards. Call within the business transaction, never trust client-supplied proof. */
public final class ComputeNormGuards {
    private ComputeNormGuards() {}
    public record DecisionRef(long id, long requestId, String kind, Instant asOf) {}

    public static void decisionReplacement(DecisionRef next, DecisionRef current, Set<Long> ancestors) {
        if (next == null || next.asOf() == null || next.id() <= 0 || next.requestId() <= 0
                || next.kind() == null || !Set.of("ADMISSION","SUBMISSION","CLOSURE").contains(next.kind()))
            throw new BizException("I-12: 决策身份和判断时间不完整");
        if (current != null && (next.requestId()!=current.requestId() || !next.kind().equals(current.kind())
                || !next.asOf().isAfter(current.asOf()) || next.id()==current.id()
                || ancestors == null || ancestors.contains(next.id())))
            throw new BizException("I-12: 替代决策必须同申请同类型、时间递增且无环");
    }

    public static void eventSource(String source, Long actionDecisionId, Long causingDecisionId) {
        if (source == null || source.isBlank() || (actionDecisionId != null && !Objects.equals(actionDecisionId,causingDecisionId)))
            throw new BizException("I-13: 事件来源缺失或动作与来源决策不一致");
    }

    public static <T> List<T> reservedCards(List<T> groupCards, List<T> usedCards) {
        if (groupCards == null || usedCards == null || groupCards.isEmpty()
                || groupCards.stream().anyMatch(Objects::isNull) || usedCards.stream().anyMatch(Objects::isNull)
                || new HashSet<>(groupCards).size()!=groupCards.size()
                || new HashSet<>(usedCards).size()!=usedCards.size() || !groupCards.containsAll(usedCards))
            throw new BizException("I-19: 使用卡必须是不重复的组内成员");
        return groupCards.stream().filter(card -> !usedCards.contains(card)).toList();
    }
}
