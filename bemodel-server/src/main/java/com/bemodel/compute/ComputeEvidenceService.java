package com.bemodel.compute;

import com.bemodel.auth.CurrentUser;
import com.bemodel.common.BizException;
import com.bemodel.common.PageResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@Profile("compute")
final class ComputeEvidenceService {
    record Evidence(long id, String source, String sourceRecordRef, Long observationId,
                    Instant observedAt, Instant validUntil, JsonNode values) {}
    record Snapshot(long id, Instant asOf, long ruleSetId, long nodeId, long requestId,
                    String actor, boolean sealed, JsonNode manifest) {}
    record Detail(Snapshot snapshot, List<Evidence> evidence, boolean authorizesExecution) {}

    private final ComputeDatabase.Store store;
    private final ComputeRuleRunner runner;
    private final ObjectMapper json;

    ComputeEvidenceService(ComputeDatabase.Store store, ComputeRuleRunner runner, ObjectMapper json) {
        this.store = store; this.runner = runner; this.json = json;
    }

    Detail capture(JsonNode body) {
        validateRequest(body);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(store.jdbc().getDataSource())));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx.execute(status -> capture(body, runner.pinActive(), now()));
    }

    static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }

    static void validateRequest(JsonNode body) {
        if (body == null || !body.isObject() || !body.path("nodeKey").isTextual()
                || body.path("nodeKey").asText().isBlank() || body.path("nodeKey").asText().length() > 64
                || !body.path("requestId").isIntegralNumber() || !body.path("requestId").canConvertToLong()
                || body.path("requestId").longValue() <= 0) {
            throw new BizException("快照需要有效的 nodeKey 和正整数 requestId");
        }
        if (body.has("asOf") || body.has("facts") || body.has("evidence") || body.has("snapshotId")) {
            throw new BizException("快照时间和证据由服务端生成，不能由调用方传入");
        }
    }

    /** Caller must already hold the business datasource's REPEATABLE_READ transaction. */
    Detail capture(JsonNode body, ComputeRuleRunner.Snapshot rules, Instant asOf) {
        validateRequest(body);
        JdbcTemplate j = store.jdbc();
        var nodes = j.queryForList("SELECT id,node_key,memory_capacity_bytes,memory_reserved_bytes,temporary_capacity_bytes,persistent_capacity_bytes FROM compute_node WHERE node_key=?", body.path("nodeKey").asText());
        if (nodes.size() != 1) throw new BizException("节点不存在");
        var node = nodes.getFirst(); long nodeId = ((Number) node.get("id")).longValue();
        var requests = j.queryForList("""
                SELECT r.id,r.status,r.employee_id,r.project_id,r.task_type_id,r.requested_gpu_count,
                       r.requested_resource_spec,t.type_code,t.priority
                FROM compute_request r JOIN task_type t ON t.id=r.task_type_id WHERE r.id=?
                """, body.path("requestId").longValue());
        if (requests.size() != 1) throw new BizException("算力申请不存在");
        var request = requests.getFirst();
        var ledger = json.createObjectNode();
        ledger.set("node", json.valueToTree(node));
        var requestValues = json.valueToTree(request);
        ((ObjectNode) requestValues).set("requested_resource_spec", read(request.get("requested_resource_spec").toString()));
        ledger.set("request", requestValues);
        var activeJobs = j.queryForList("""
                SELECT j.id,j.request_id,j.task_type_id,j.effective_gpu_count,j.effective_resource_spec,j.status
                FROM active_job a JOIN compute_job j ON j.id=a.job_id AND j.request_id=a.request_id
                WHERE a.request_id=?
                """, body.path("requestId").longValue());
        if (!activeJobs.isEmpty()) {
            ObjectNode job = json.valueToTree(activeJobs.getFirst());
            job.set("effective_resource_spec", read(activeJobs.getFirst().get("effective_resource_spec").toString()));
            ledger.set("activeJob", job);
        }
        ledger.set("groups", json.valueToTree(j.queryForList("SELECT id,group_code,current_hold_id,pending_allocation_id FROM gpu_group WHERE node_id=? ORDER BY id", nodeId)));
        ledger.set("holds", json.valueToTree(j.queryForList("""
                SELECT h.id,h.group_id,h.deployment_id,h.deployment_revision_id,h.allocation_id,
                       DATE_FORMAT(h.started_at,'%Y-%m-%dT%H:%i:%s.%fZ') AS started_at
                FROM hold_period h JOIN gpu_group g ON g.id=h.group_id
                WHERE g.node_id=? AND h.ended_at IS NULL ORDER BY h.id
                """, nodeId)));
        ledger.set("allocations", json.valueToTree(j.queryForList("""
                SELECT a.id,a.job_id,a.group_id,a.status,a.memory_bytes,a.temporary_bytes,a.persistent_bytes
                FROM gpu_allocation a JOIN gpu_group g ON g.id=a.group_id
                WHERE g.node_id=? AND a.status IN ('PENDING','OCCUPIED') ORDER BY a.id
                """, nodeId)));
        ledger.set("deploymentBaselines", json.valueToTree(j.queryForList("""
                SELECT d.id,d.group_id,d.status,d.preemptible,r.id AS revision_id,r.version,
                       r.memory_bytes,r.temporary_bytes,r.persistent_bytes
                FROM deployment d JOIN deployment_revision r ON r.deployment_id=d.id
                JOIN hold_period h ON h.deployment_revision_id=r.id AND h.deployment_id=d.id AND h.ended_at IS NULL
                JOIN gpu_group g ON g.id=d.group_id WHERE g.node_id=? ORDER BY d.id
                """, nodeId)));
        ledger.set("allocationCards", json.valueToTree(j.queryForList("""
                SELECT ag.allocation_id,ag.gpu_id FROM allocation_gpu ag
                JOIN gpu_allocation a ON a.id=ag.allocation_id JOIN gpu_group g ON g.id=a.group_id
                WHERE g.node_id=? AND a.status IN ('PENDING','OCCUPIED') ORDER BY ag.allocation_id,ag.gpu_id
                """, nodeId)));
        ledger.set("queue", json.valueToTree(j.queryForList("""
                SELECT q.request_id,q.target_group_id,q.priority,q.status,q.reason_code,
                       DATE_FORMAT(q.submitted_at,'%Y-%m-%dT%H:%i:%s.%fZ') AS submitted_at
                FROM compute_queue q LEFT JOIN gpu_group g ON g.id=q.target_group_id
                WHERE g.node_id=? OR q.target_group_id IS NULL ORDER BY q.priority DESC,q.submitted_at,q.request_id
                """, nodeId)));
        ledger.put("ledgerIsHistorical", false);
        ledger.put("readAt", asOf.toString());

        JsonNode settings = read(j.queryForObject("SELECT settings FROM rule_set WHERE id=?", String.class, rules.ruleSetId()));
        JsonNode gpuPolicy = policy(settings, "GPU_OBSERVATION");
        JsonNode nodePolicy = policy(settings, "NODE_WATERMARK");
        long ruleMaxAge = rules.rules().stream().filter(r -> "R-11".equals(r.ruleCode())).findFirst().orElseThrow()
                .definition().path("parameters").path("maxAgeSeconds").asLong();
        var manifest = json.createObjectNode().put("inputSource", "SERVER_SNAPSHOT")
                .put("ledgerIsHistorical", false).put("maxAgeSeconds", ruleMaxAge);
        manifest.set("dataPolicies", json.valueToTree(List.of(gpuPolicy, nodePolicy)));
        manifest.set("ruleVersions", json.valueToTree(rules.rules().stream().map(r -> Map.of(
                "ruleCode", r.ruleCode(), "ruleVersionId", r.ruleVersionId(), "ruleVersion", r.ruleVersion(),
                "definitionVersion", r.definitionVersion())).toList()));
        var cards = j.queryForList("SELECT id,gpu_uuid,gpu_index,group_id,vram_bytes FROM gpu_device WHERE node_id=? ORDER BY gpu_index", nodeId);
        ledger.set("cards", json.valueToTree(cards));
        var observations = j.queryForList("""
                WITH ranked AS (
                  SELECT o.*,ROW_NUMBER() OVER(PARTITION BY subject_kind,subject_id ORDER BY observed_at DESC,id DESC) AS rn
                  FROM resource_observation o WHERE node_id=? AND observed_at<=? AND subject_kind IN ('GPU','NODE')
                ) SELECT * FROM ranked WHERE rn=1
                """, nodeId, utc(asOf));
        var issues = new ArrayList<String>();
        var pending = new ArrayList<Evidence>();
        pending.add(new Evidence(0, "PLATFORM_LEDGER", "node:" + nodeId + "/request:" + body.path("requestId").asLong(),
                null, asOf, asOf, ledger));
        if (cards.isEmpty()) issues.add("MISSING_GPU_INVENTORY");
        for (var card : cards) collect(pending, issues, observations, "GPU", ((Number) card.get("id")).longValue(),
                card.get("gpu_uuid").toString(), gpuPolicy, asOf, ruleMaxAge);
        collect(pending, issues, observations, "NODE", nodeId, node.get("node_key").toString(), nodePolicy, asOf, ruleMaxAge);
        manifest.set("issues", json.valueToTree(issues));
        manifest.put("usable", issues.isEmpty());
        long id = ComputeReleaseBridge.insert(j, """
                INSERT INTO evidence_snapshot(as_of,rule_set_id,node_id,request_id,actor,manifest)
                VALUES(?,?,?,?,?,?)
                """, utc(asOf), rules.ruleSetId(), nodeId, body.path("requestId").longValue(),
                Objects.requireNonNullElse(CurrentUser.username(), "system"), manifest.toString());
        for (var e : pending) j.update("""
                INSERT INTO evidence(snapshot_id,source,source_record_ref,observation_id,observed_at,valid_until,values_json)
                VALUES(?,?,?,?,?,?,?)
                """, id, e.source(), e.sourceRecordRef(), e.observationId(), utc(e.observedAt()), utc(e.validUntil()), e.values().toString());
        j.update("UPDATE evidence_snapshot SET sealed=TRUE WHERE id=?", id);
        return detail(id);
    }

    private JsonNode policy(JsonNode settings, String code) {
        for (JsonNode asset : settings.path("dataAssets")) if (code.equals(asset.path("asset_code").asText())) {
            if (!asset.path("authoritative_source").isTextual() || asset.path("authoritative_source").asText().isBlank()
                    || asset.hasNonNull("max_age_seconds") && (!asset.path("max_age_seconds").canConvertToLong()
                    || asset.path("max_age_seconds").asLong() <= 0)) break;
            var policy = json.createObjectNode();
            for (String key : List.of("asset_code", "version", "source_kind", "authoritative_source", "max_age_seconds")) policy.set(key, asset.path(key));
            return policy;
        }
        throw new BizException("生效集合缺少证据来源或时效配置: " + code);
    }

    private void collect(List<Evidence> evidence, List<String> issues, List<Map<String,Object>> rows,
                         String kind, long subjectId, String sourceKey, JsonNode policy, Instant asOf, long ruleMaxAge) {
        var matched = rows.stream().filter(r -> kind.equals(r.get("subject_kind"))
                && ((Number) r.get("subject_id")).longValue() == subjectId).findFirst();
        String subject = kind + ":" + subjectId;
        if (matched.isEmpty()) { issues.add(subject + ":MISSING_OBSERVATION"); return; }
        var row = matched.get();
        Instant observed = instant(row.get("observed_at")), until = instant(row.get("valid_until"));
        var values = json.createObjectNode().put("subjectKind", kind).put("subjectId", subjectId)
                .put("sourceSubjectKey", Objects.toString(row.get("source_subject_key"), ""))
                .put("status", row.get("status").toString());
        JsonNode raw = row.get("values_json") == null ? json.nullNode() : read(row.get("values_json").toString());
        List<String> keys = "GPU".equals(kind) ? List.of("healthy", "occupied")
                : List.of("memoryUsedBytes", "temporaryUsedBytes", "persistentUsedBytes");
        var reasons = new ArrayList<String>();
        if (!"OK".equals(row.get("status"))) reasons.add("OBSERVATION_ERROR");
        if (!policy.path("authoritative_source").asText().equals(row.get("source"))) reasons.add("SOURCE_MISMATCH");
        if (!Set.of("PLATFORM", "SIMULATED", "CONNECTED").contains(policy.path("source_kind").asText())) reasons.add("SOURCE_NOT_CONNECTED");
        if (!kind.equals(row.get("source_subject_kind")) || !sourceKey.equals(row.get("source_subject_key"))) reasons.add("SUBJECT_MISMATCH");
        for (String key : keys) {
            JsonNode value = raw.path(key);
            boolean valid = "GPU".equals(kind) ? value.isBoolean()
                    : value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0;
            if (!valid) reasons.add("INVALID_VALUE:" + key);
            else values.set(key, value);
        }
        long maxAge = policy.hasNonNull("max_age_seconds") ? Math.min(ruleMaxAge, policy.path("max_age_seconds").asLong()) : ruleMaxAge;
        if (!ComputeResourceMath.evidenceValid(observed, until, asOf, maxAge)) reasons.add("STALE_EVIDENCE");
        values.put("maxAgeSeconds", maxAge);
        values.put("usable", reasons.isEmpty());
        values.set("issues", json.valueToTree(reasons));
        for (String reason : reasons) issues.add(subject + ":" + reason);
        evidence.add(new Evidence(0, row.get("source").toString(), "resource_observation:" + row.get("id"),
                ((Number) row.get("id")).longValue(), observed, until, values));
    }

    PageResult<Snapshot> list(int pageNum, int pageSize) {
        JdbcTemplate j = store.jdbc();
        long total = j.queryForObject("SELECT COUNT(*) FROM evidence_snapshot WHERE sealed=TRUE AND manifest IS NOT NULL", Long.class);
        var rows = j.query("SELECT * FROM evidence_snapshot WHERE sealed=TRUE AND manifest IS NOT NULL ORDER BY id DESC LIMIT ? OFFSET ?",
                (r, i) -> snapshot(r), pageSize, (pageNum - 1L) * pageSize);
        return PageResult.of(rows, total, pageNum, pageSize);
    }

    Detail detail(long id) {
        JdbcTemplate j = store.jdbc();
        var snapshots = j.query("SELECT * FROM evidence_snapshot WHERE id=? AND sealed=TRUE AND manifest IS NOT NULL", (r,i) -> snapshot(r), id);
        if (snapshots.isEmpty()) throw new BizException("证据快照不存在");
        var evidence = j.query("SELECT * FROM evidence WHERE snapshot_id=? ORDER BY id", (r,i) -> new Evidence(
                r.getLong("id"), r.getString("source"), r.getString("source_record_ref"), (Long) r.getObject("observation_id"),
                r.getObject("observed_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                r.getObject("valid_until", LocalDateTime.class).toInstant(ZoneOffset.UTC), read(r.getString("values_json"))), id);
        return new Detail(snapshots.getFirst(), evidence, false);
    }

    private Snapshot snapshot(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Snapshot(r.getLong("id"), r.getObject("as_of", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                r.getLong("rule_set_id"), r.getLong("node_id"), r.getLong("request_id"), r.getString("actor"),
                r.getBoolean("sealed"), read(r.getString("manifest")));
    }

    private JsonNode read(String value) {
        try { return json.readTree(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new BizException("证据来源 JSON 无法解析"); }
    }
    private static Instant instant(Object value) { return ((LocalDateTime) value).toInstant(ZoneOffset.UTC); }
    private static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
}
