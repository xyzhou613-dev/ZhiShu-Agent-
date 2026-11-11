package com.bemodel.compute;

import com.bemodel.modeling.entity.Rule;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Deterministic M-02 checks. No database writes, commands, LLM calls or allocation authority. */
public final class ComputeRuleEvaluator {
    public static final String VERSION = "a800-m02-v1";
    public static final List<String> CODES = List.of("R-01", "R-02", "R-03", "R-04", "R-05", "R-06", "R-07",
            "R-08", "R-10", "R-11", "R-12", "R-13", "R-14", "R-15");
    private static final List<String> RESOURCES = List.of("memoryBytes", "temporaryBytes", "persistentBytes");

    public enum Status { PASS, FAIL, NOT_APPLICABLE, ERROR }
    public record Check(Status result, String reasonCode, Map<String, Object> output) {}
    public record Result(String ruleCode, int ruleVersion, String evaluatorVersion, Status result,
                         String reasonCode, Map<String, Object> output, boolean dryRun, boolean authorizesExecution) {}

    public Result evaluate(Rule rule, JsonNode definition, JsonNode facts) {
        Check check;
        try {
            require(facts != null && facts.isObject(), "facts 必须是 JSON 对象");
            check = switch (rule.getRuleCode()) {
                case "R-01" -> specification(facts);
                case "R-02" -> capacity(facts, false);
                case "R-03" -> resources(facts);
                case "R-04" -> selectGroup(facts);
                case "R-05" -> actionGuard(facts);
                case "R-06" -> closure(facts);
                case "R-07" -> reservationReceipt(facts);
                case "R-08" -> notification(facts);
                case "R-10" -> audit(facts);
                case "R-11" -> freshness(facts, number(definition.path("parameters"), "maxAgeSeconds"));
                case "R-12" -> queueHead(facts);
                case "R-13" -> preemption(facts);
                case "R-14" -> health(facts);
                case "R-15" -> restore(facts);
                default -> throw new IllegalArgumentException("不支持的规则编号");
            };
        } catch (IllegalArgumentException | ArithmeticException e) {
            boolean invalidSpec = "R-01".equals(rule.getRuleCode());
            check = new Check(invalidSpec ? Status.FAIL : Status.ERROR, invalidSpec ? "DENY_SPEC" : "INVALID_FACTS", Map.of("message", e.getMessage()));
        }
        return new Result(rule.getRuleCode(), rule.getVersion(), VERSION, check.result(), check.reasonCode(),
                check.output(), true, false);
    }

    static Check specification(JsonNode f) {
        long count = positive(f, "gpuCount");
        text(f, "taskType");
        JsonNode spec = object(f, "resourceSpec");
        positive(spec, "vramBytesPerGpu");
        positive(spec, "memoryBytes");
        number(spec, "temporaryBytes");
        number(spec, "persistentBytes");
        return count <= 4 ? pass(Map.of("gpuCount", count)) : fail("QUEUE_NO_TARGET");
    }

    /** Components must be computed from one authoritative ledger; new reservation is excluded from existing totals. */
    static Check capacity(JsonNode f, boolean restoring) {
        JsonNode cap = object(f, "capacityBytes");
        JsonNode deployments = object(f, restoring ? "otherDeploymentBytes" : "continuingDeploymentBytes");
        JsonNode reservations = object(f, "effectiveReservationBytes");
        JsonNode added = object(f, restoring ? "restoreDeploymentBytes" : "newReservationBytes");
        Map<String, Long> projected = new LinkedHashMap<>();
        boolean enough = true;
        for (String resource : RESOURCES) {
            long sum = Math.addExact(Math.addExact(number(deployments, resource), number(reservations, resource)), number(added, resource));
            projected.put(resource, sum);
            enough &= sum <= number(cap, resource);
        }
        return outcome(enough, restoring ? "RECOVERY_NO_CAPACITY" : "QUEUE_NO_CAPACITY", Map.of("projectedBytes", projected));
    }

    static Check resources(JsonNode f) {
        long requiredVram = positive(f, "vramBytesPerGpu");
        JsonNode cards = array(f, "selectedGpuVramBytes", true);
        boolean enough = true;
        for (JsonNode card : cards) enough &= integer(card, "selectedGpuVramBytes[]") >= requiredVram;
        JsonNode required = object(f, "requiredBytes");
        JsonNode available = object(f, "availableBytes");
        for (String resource : RESOURCES) enough &= number(required, resource) <= number(available, resource);
        String wanted = text(f, "requiredInterconnect");
        String actual = text(f, "groupInterconnect");
        enough &= "NONE".equals(wanted) || wanted.equals(actual);
        return outcome(enough, "QUEUE_RESOURCE_SHORT", Map.of());
    }

    static Check selectGroup(JsonNode f) {
        long count = positive(f, "gpuCount");
        if (count >= 5) return fail("QUEUE_NO_TARGET");
        String target = count <= 2 ? "B" : "A";
        if (count == 1) {
            JsonNode flex = object(f, "flex");
            boolean healthy = bool(flex, "healthy"), fresh = bool(flex, "evidenceFresh");
            boolean held = bool(flex, "held"), reserved = bool(flex, "reserved"), occupied = bool(flex, "occupied");
            if (healthy && fresh && !held && !reserved && !occupied) target = "FLEX";
        }
        return pass(Map.of("targetGroup", target, "requiresFeasibilityChecks", List.of("R-02", "R-03", "R-14")));
    }

    static Check queueHead(JsonNode f) {
        long requestId = positive(f, "requestId");
        JsonNode queue = array(f, "queue", false);
        record Entry(long id, long priority, Instant submitted) {}
        List<Entry> entries = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        for (JsonNode row : queue) {
            long id = positive(row, "requestId");
            require(ids.add(id), "队列申请编号不能重复");
            entries.add(new Entry(id, number(row, "priority"), time(row, "submittedAt")));
        }
        entries.sort(Comparator.comparingLong(Entry::priority).reversed().thenComparing(Entry::submitted).thenComparingLong(Entry::id));
        List<Long> order = entries.stream().map(Entry::id).toList();
        return outcome(!order.isEmpty() && order.getFirst() == requestId, "QUEUE_NOT_HEAD", Map.of("queueOrder", order));
    }

    static Check preemption(JsonNode f) {
        if (!bool(f, "preemptionRequired")) return new Check(Status.NOT_APPLICABLE, "PREEMPTION_NOT_REQUIRED", Map.of());
        String kind = text(f, "holderKind"), taskType = text(f, "taskType");
        String group = text(f, "groupCode");
        boolean allowed = bool(f, "preemptible");
        long residentCount = positive(f, "deploymentGpuCount");
        long requestPriority = number(f, "requestPriority"), deploymentPriority = number(f, "deploymentPriority");
        return outcome("TRAIN".equals(taskType) && "DEPLOYMENT".equals(kind) && allowed && Set.of("A", "B").contains(group) && residentCount > 1
                && requestPriority > deploymentPriority, "QUEUE_NOT_PREEMPTIBLE", Map.of());
    }

    static Check health(JsonNode f) {
        long requested = positive(f, "gpuCount");
        JsonNode cards = array(f, "groupCards", true), used = array(f, "usedGpuIds", true);
        Map<Long, Boolean> healthy = new HashMap<>();
        for (JsonNode card : cards) {
            long id = positive(card, "gpuId");
            require(!healthy.containsKey(id), "组内 GPU 编号不能重复");
            healthy.put(id, bool(card, "healthy"));
        }
        Set<Long> selected = new HashSet<>();
        for (JsonNode id : used) require(selected.add(integer(id, "usedGpuIds[]")), "使用卡不能重复");
        boolean ok = requested == selected.size() && selected.stream().allMatch(id -> Boolean.TRUE.equals(healthy.get(id)));
        return outcome(ok, "QUEUE_GROUP_UNHEALTHY", Map.of("usedGpuIds", selected.stream().sorted().toList()));
    }

    static Check restore(JsonNode f) {
        JsonNode cards = array(f, "groupCards", true);
        boolean healthy = true;
        Set<Long> ids = new HashSet<>();
        for (JsonNode card : cards) {
            require(ids.add(positive(card, "gpuId")), "组内 GPU 编号不能重复");
            healthy &= bool(card, "healthy");
        }
        Check projected = capacity(f, true);
        return healthy ? projected : fail("RECOVERY_UNHEALTHY");
    }

    static Check freshness(JsonNode f, long maxAge) {
        require(maxAge > 0, "maxAgeSeconds 必须大于零");
        Instant asOf = time(f, "asOf");
        for (JsonNode row : array(f, "evidence", true)) {
            Instant observed = time(row, "observedAt"), until = time(row, "validUntil");
            if (observed.isAfter(asOf) || until.isBefore(asOf)
                    || Duration.between(observed, asOf).compareTo(Duration.ofSeconds(maxAge)) > 0) return fail("QUEUE_STALE_EVIDENCE");
        }
        return pass(Map.of("asOf", asOf.toString(), "maxAgeSeconds", maxAge));
    }

    /** Checks an already committed transaction receipt; a successful pre-check can never substitute for a write. */
    static Check reservationReceipt(JsonNode f) {
        boolean duplicate = bool(f, "duplicateRequest");
        if (duplicate) return fail("DENY_DUPLICATE");
        boolean committed = bool(f, "transactionCommitted"), locks = bool(f, "nodeAndGroupLocked"), head = bool(f, "queueHeadRechecked");
        long pending = number(f, "pendingAllocationCount"), holds = number(f, "openHoldCount");
        long allocation = positive(f, "allocationId");
        return outcome(committed && locks && head && pending == 1 && holds <= 1 && bool(f, "capacityRechecked"),
                "QUEUE_CONFLICT", Map.of("allocationId", allocation, "receiptOnly", true));
    }

    static Check actionGuard(JsonNode f) {
        String step = text(f, "step");
        require(Set.of("NOTIFY", "DRAIN", "STOP", "HANDOVER", "START_JOB").contains(step), "未知执行步骤");
        if (!bool(f, "reservationValid")) return fail("QUEUE_CONFLICT");
        if ("NOTIFY".equals(step)) return pass(Map.of("nextAction", "NOTIFY"));
        if (!"SUCCEEDED".equals(receipt(f, "notificationStatus"))) return failedAction("NOTIFY_FAILED", "CANCEL_AND_COMPENSATE");
        if ("DRAIN".equals(step)) return pass(Map.of("nextAction", "DRAIN"));
        String drain = receipt(f, "drainStatus");
        if (!"SUCCEEDED".equals(drain) || number(f, "inFlightRequests") != 0) return failedAction("ABORT_INFLIGHT", "CANCEL_AND_COMPENSATE");
        if ("STOP".equals(step)) return pass(Map.of("nextAction", "STOP_DEPLOYMENT"));
        String stopped = receipt(f, "stopStatus");
        if (!"SUCCEEDED".equals(stopped) || !bool(f, "gpuReleased")) return failedAction("ABORT_INFLIGHT", "RECONCILE_BEFORE_HANDOVER");
        if ("HANDOVER".equals(step)) return pass(Map.of("nextAction", "ATOMIC_HANDOVER", "requiresTransaction", true));
        return bool(f, "handoverCommitted") ? pass(Map.of("nextAction", "START_JOB"))
                : failedAction("QUEUE_CONFLICT", "RECONCILE_BEFORE_START");
    }

    static Check notification(JsonNode f) {
        String event = text(f, "event");
        require(Set.of("PREEMPTION", "RESTORED").contains(event), "通知事件必须是 PREEMPTION 或 RESTORED");
        Set<String> recipients = new LinkedHashSet<>();
        for (JsonNode item : array(f, "recipients", true)) {
            require(item.isTextual() && !item.asText().isBlank(), "通知对象必须是稳定编号");
            recipients.add(item.asText());
        }
        String status = receipt(f, "deliveryStatus");
        Map<String, Object> output = Map.of("event", event, "recipients", recipients,
                "failureRoute", "PREEMPTION".equals(event) ? "CANCEL_AND_COMPENSATE" : "RETRY_NOTIFICATION");
        return outcome("SUCCEEDED".equals(status), "NOTIFY_FAILED", output);
    }

    static Check closure(JsonNode f) {
        require(bool(f, "jobExitConfirmed"), "未确认作业退出，不能收尾");
        JsonNode candidate = f.get("handoffCandidate");
        Map<String, Object> checks = new LinkedHashMap<>();
        if (candidate != null && !candidate.isNull()) {
            boolean target = bool(candidate, "targetStillMatches");
            checks.put("targetStillMatches", target);
            if (target) {
                checks.put("R-12", queueHead(object(candidate, "queueFacts")));
                checks.put("R-02", capacity(object(candidate, "capacityFacts"), false));
                checks.put("R-03", resources(object(candidate, "resourceFacts")));
                checks.put("R-14", health(object(candidate, "healthFacts")));
                if (checks.values().stream().filter(Check.class::isInstance).map(Check.class::cast).allMatch(c -> c.result() == Status.PASS)) {
                    return pass(Map.of("outcome", "HANDOFF", "nextAction", "RESERVE_NEXT_BEFORE_HANDOVER", "checks", checks));
                }
            }
        }
        Check recovery = restore(object(f, "restoreFacts"));
        checks.put("R-15", recovery);
        return new Check(Status.PASS, recovery.result() == Status.PASS ? "RESTORE" : "WAIT_RESOURCE",
                Map.of("outcome", recovery.result() == Status.PASS ? "RESTORE" : "WAIT_RESOURCE", "checks", checks));
    }

    static Check audit(JsonNode f) {
        if (!f.path("snapshotId").isTextual() || f.path("snapshotId").asText().isBlank()
                || !f.path("asOf").isTextual() || !f.path("checks").isArray() || f.path("checks").isEmpty()
                || !f.path("evidence").isArray()) return fail("AUDIT_INCOMPLETE");
        time(f, "asOf");
        Set<String> evidenceIds = new HashSet<>();
        for (JsonNode evidence : f.path("evidence")) {
            String id = text(evidence, "evidenceId");
            require(evidenceIds.add(id), "证据编号不能重复");
            text(evidence, "source");
            time(evidence, "observedAt");
            time(evidence, "validUntil");
            if (!text(f, "snapshotId").equals(text(evidence, "snapshotId"))) return fail("AUDIT_INCOMPLETE");
        }
        boolean hasPath = false;
        for (JsonNode check : f.path("checks")) {
            if (!CODES.contains(check.path("ruleCode").asText()) || !check.path("ruleVersion").canConvertToInt()
                    || !check.path("ruleVersion").isIntegralNumber() || check.path("ruleVersion").asInt() < 1
                    || !Set.of("PASS", "FAIL", "NOT_APPLICABLE", "ERROR").contains(check.path("result").asText())
                    || !check.path("evidenceIds").isArray()) return fail("AUDIT_INCOMPLETE");
            for (JsonNode id : check.path("evidenceIds")) {
                if (!id.isTextual() || !evidenceIds.contains(id.asText())) return fail("AUDIT_INCOMPLETE");
                hasPath = true;
            }
        }
        return outcome(hasPath, "AUDIT_INCOMPLETE", Map.of("evidenceCount", evidenceIds.size()));
    }

    private static Check pass(Map<String, Object> out) { return new Check(Status.PASS, "PASS", out); }
    private static Check fail(String reason) { return new Check(Status.FAIL, reason, Map.of()); }
    private static Check outcome(boolean ok, String reason, Map<String, Object> out) { return new Check(ok ? Status.PASS : Status.FAIL, ok ? "PASS" : reason, out); }
    private static Check failedAction(String reason, String route) { return new Check(Status.FAIL, reason, Map.of("failureRoute", route)); }
    private static void require(boolean value, String message) { if (!value) throw new IllegalArgumentException(message); }
    private static JsonNode object(JsonNode n, String key) { JsonNode v = n.path(key); require(v.isObject(), key + " 必须是对象"); return v; }
    private static JsonNode array(JsonNode n, String key, boolean nonempty) { JsonNode v = n.path(key); require(v.isArray() && (!nonempty || !v.isEmpty()), key + " 必须是" + (nonempty ? "非空" : "") + "数组"); return v; }
    private static String text(JsonNode n, String key) { JsonNode v = n.path(key); require(v.isTextual() && !v.asText().isBlank(), key + " 必须是非空字符串"); return v.asText(); }
    private static boolean bool(JsonNode n, String key) { JsonNode v = n.path(key); require(v.isBoolean(), key + " 必须是布尔值，未知不能按 false 代入"); return v.booleanValue(); }
    private static long number(JsonNode n, String key) { return integer(n.path(key), key); }
    private static long positive(JsonNode n, String key) { long value = number(n, key); require(value > 0, key + " 必须大于零"); return value; }
    private static long integer(JsonNode v, String label) { require(v.isIntegralNumber() && v.canConvertToLong() && v.longValue() >= 0, label + " 必须是非负 64 位整数"); return v.longValue(); }
    private static Instant time(JsonNode n, String key) { try { return Instant.parse(text(n, key)); } catch (java.time.DateTimeException e) { throw new IllegalArgumentException(key + " 必须是带时区的 ISO 时间"); } }
    private static String receipt(JsonNode n, String key) { String value = text(n, key); require(Set.of("SUCCEEDED", "FAILED", "TIMED_OUT", "UNKNOWN", "PENDING").contains(value), key + " 回执状态非法"); return value; }
}
