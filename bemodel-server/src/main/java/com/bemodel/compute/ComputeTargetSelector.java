package com.bemodel.compute;

import com.bemodel.common.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static com.bemodel.compute.ComputeRuleEvaluator.Status;

/** R-04 chooses a target; availability here is explanatory and grants no allocation authority. */
final class ComputeTargetSelector {
    record Choice(long gpuCount, String specBasis, Long jobId, Long targetGroupId, String targetGroupCode,
                  Status ruleResult, String reasonCode, String selectionStatus, JsonNode facts, JsonNode output,
                  List<Long> evidenceIds, ComputeRuleRunner.ExecutionRule rule) {}
    private record GroupState(JsonNode group, boolean held, boolean reserved, boolean consistent,
                              boolean evidenceFresh, boolean healthy, boolean occupied, boolean deploymentHeld,
                              List<String> blockers, List<Long> evidenceIds) {}
    private final ObjectMapper json;
    ComputeTargetSelector(ObjectMapper json) { this.json = json; }

    Choice select(ComputeEvidenceService.Detail captured, ComputeRuleRunner.Snapshot rules, JsonNode settings) {
        if (!captured.snapshot().sealed() || captured.snapshot().ruleSetId() != rules.ruleSetId())
            throw new BizException("目标选择需要同一生效集合的已封存快照");
        var ledgerEvidence = captured.evidence().stream().filter(e -> "PLATFORM_LEDGER".equals(e.source())).findFirst()
                .orElseThrow(() -> new BizException("快照缺少平台账本"));
        JsonNode ledger = ledgerEvidence.values();
        validateLayout(ledger, settings);
        JsonNode job = ledger.path("activeJob");
        boolean useJob = job.isObject();
        JsonNode count = useJob ? job.path("effective_gpu_count") : ledger.path("request").path("requested_gpu_count");
        if (!count.isIntegralNumber() || !count.canConvertToLong() || count.longValue() <= 0)
            throw new BizException("快照缺少有效卡数");
        long gpuCount = count.longValue();
        var facts = json.createObjectNode().put("gpuCount", gpuCount);
        GroupState flex = null;
        if (gpuCount == 1) {
            flex = state("FLEX", ledger, captured);
            facts.putObject("flex").put("healthy", flex.healthy()).put("evidenceFresh", flex.evidenceFresh() && flex.consistent())
                    .put("held", flex.held()).put("reserved", flex.reserved()).put("occupied", flex.occupied());
        }
        var rule = rules.rules().stream().filter(r -> "R-04".equals(r.ruleCode())).findFirst()
                .orElseThrow(() -> new BizException("生效集合缺少 R-04"));
        var result = new ComputeRuleEvaluator().evaluateSnapshot("R-04", rule.ruleVersion(), rule.definition(), facts);
        String code = Objects.toString(result.output().get("targetGroup"), null);
        GroupState target = code == null ? null : state(code, ledger, captured);
        String status;
        if (target == null) status = "WAITING";
        else if (!target.consistent() || target.reserved() || !target.evidenceFresh() || !target.healthy()) status = "WAITING";
        else if (target.held() && target.deploymentHeld()) status = "PREEMPTION_CHECK_REQUIRED";
        else if (target.held() || target.occupied()) status = "WAITING";
        else status = "TARGET_SELECTED";
        var output = json.valueToTree(result.output());
        var details = (com.fasterxml.jackson.databind.node.ObjectNode) output;
        details.put("selectionStatus", status).put("specBasis", useJob ? "JOB" : "REQUEST")
                .put("gpuCount", gpuCount).put("queuePersisted", false).put("authorizesExecution", false);
        List<String> blockers = target == null ? List.of("QUEUE_NO_TARGET") : target.blockers();
        details.set("blockingReasons", json.valueToTree(blockers));
        if (target != null) details.put("targetGroupId", target.group().path("id").longValue());
        var references = new LinkedHashSet<Long>();
        references.add(ledgerEvidence.id());
        if (flex != null) references.addAll(flex.evidenceIds());
        if (target != null) references.addAll(target.evidenceIds());
        return new Choice(gpuCount, useJob ? "JOB" : "REQUEST", useJob ? job.path("id").longValue() : null,
                target == null ? null : target.group().path("id").longValue(), code, result.result(), result.reasonCode(),
                status, facts, details, List.copyOf(references), rule);
    }

    private void validateLayout(JsonNode ledger, JsonNode settings) {
        Map<String,Integer> layout = Map.of("A",4,"B",2,"C",1,"FLEX",1);
        if (!settings.path("nodeKey").isTextual() || !settings.path("nodeKey").equals(ledger.path("node").path("node_key"))
                || settings.path("groups").size() != 4 || ledger.path("groups").size() != 4)
            throw new BizException("节点卡组配置与生效集合不一致，请核对后重新发布");
        var seen = new HashSet<String>();
        for (JsonNode published : settings.path("groups")) {
            String code = published.path("group_code").asText();
            JsonNode actual = find(ledger.path("groups"), "group_code", code);
            long cards = list(ledger.path("cards")).stream().filter(c -> c.path("group_id").asLong() == published.path("id").asLong()).count();
            if (!seen.add(code) || !layout.containsKey(code) || actual == null
                    || actual.path("id").asLong() != published.path("id").asLong() || cards != layout.get(code)
                    || published.path("cards").asLong() != cards)
                throw new BizException("节点卡组配置与生效集合不一致，请核对后重新发布");
        }
    }

    private GroupState state(String code, JsonNode ledger, ComputeEvidenceService.Detail captured) {
        JsonNode group = find(ledger.path("groups"), "group_code", code);
        if (group == null) throw new BizException("快照缺少目标卡组");
        long id = group.path("id").longValue();
        var holds = list(ledger.path("holds")).stream().filter(h -> h.path("group_id").asLong() == id).toList();
        var allocations = list(ledger.path("allocations")).stream().filter(a -> a.path("group_id").asLong() == id).toList();
        var pending = allocations.stream().filter(a -> "PENDING".equals(a.path("status").asText())).toList();
        boolean consistent = pointerMatches(group.path("current_hold_id"), holds) && pointerMatches(group.path("pending_allocation_id"), pending);
        for (JsonNode a : allocations) if ("OCCUPIED".equals(a.path("status").asText())
                && holds.stream().noneMatch(h -> h.path("allocation_id").asLong() == a.path("id").asLong())) consistent = false;
        for (JsonNode h : holds) if (h.hasNonNull("allocation_id") && allocations.stream().noneMatch(a ->
                "OCCUPIED".equals(a.path("status").asText()) && a.path("id").asLong() == h.path("allocation_id").asLong())) consistent = false;
        boolean held = !holds.isEmpty() || group.hasNonNull("current_hold_id")
                || allocations.stream().anyMatch(a -> "OCCUPIED".equals(a.path("status").asText()));
        boolean reserved = !pending.isEmpty() || group.hasNonNull("pending_allocation_id");
        boolean fresh = true, healthy = true, occupied = false;
        var refs = new ArrayList<Long>();
        for (JsonNode card : list(ledger.path("cards"))) if (card.path("group_id").asLong() == id) {
            var observation = captured.evidence().stream().filter(e -> e.observationId() != null
                    && "GPU".equals(e.values().path("subjectKind").asText())
                    && e.values().path("subjectId").asLong() == card.path("id").asLong()).findFirst();
            if (observation.isEmpty()) { fresh = false; healthy = false; continue; }
            var e = observation.get(); refs.add(e.id());
            boolean usable = e.values().path("usable").asBoolean(false)
                    && e.values().path("healthy").isBoolean() && e.values().path("occupied").isBoolean()
                    && ComputeResourceMath.evidenceValid(e.observedAt(), e.validUntil(), captured.snapshot().asOf(), e.values().path("maxAgeSeconds").asLong());
            fresh &= usable;
            healthy &= usable && e.values().path("healthy").booleanValue();
            occupied |= usable && e.values().path("occupied").booleanValue();
        }
        var blockers = new ArrayList<String>();
        if (!consistent) blockers.add("GROUP_LEDGER_MISMATCH");
        if (held) blockers.add("GROUP_HELD");
        if (reserved) blockers.add("GROUP_RESERVED");
        if (!fresh) blockers.add("GPU_EVIDENCE_UNAVAILABLE");
        if (fresh && !healthy) blockers.add("GPU_UNHEALTHY");
        if (occupied) blockers.add("GPU_OCCUPIED");
        boolean deploymentHeld = holds.size() == 1 && holds.getFirst().hasNonNull("deployment_id");
        return new GroupState(group, held, reserved, consistent, fresh, healthy, occupied, deploymentHeld, List.copyOf(blockers), List.copyOf(refs));
    }

    private static boolean pointerMatches(JsonNode pointer, List<JsonNode> records) {
        return records.isEmpty() ? pointer.isNull() || pointer.isMissingNode()
                : records.size() == 1 && pointer.isIntegralNumber() && pointer.asLong() == records.getFirst().path("id").asLong();
    }
    private static JsonNode find(JsonNode rows, String key, String value) {
        for (JsonNode row : rows) if (value.equals(row.path(key).asText())) return row;
        return null;
    }
    private static List<JsonNode> list(JsonNode rows) {
        var values = new ArrayList<JsonNode>(); rows.forEach(values::add); return values;
    }
}
