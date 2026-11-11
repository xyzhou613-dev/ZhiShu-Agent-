package com.bemodel.compute;

import com.bemodel.auth.CurrentUser;
import com.bemodel.common.BizException;
import com.bemodel.common.PageResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

import static com.bemodel.compute.ComputeRuleEvaluator.Status;

@Service
@Profile("compute")
final class ComputeRuleEvaluationService {
    record Run(long id, String stage, long ruleSetId, long sourceReleaseId, String sourceVersionTag,
               String evaluatorVersion, String formulaVersion, String actor, Instant evaluatedAt,
               String inputSource, Status overallResult, boolean authorizesExecution, Long snapshotId, Instant asOf) {}
    record Check(long ruleVersionId, String ruleCode, int ruleVersion, int definitionVersion,
                 Status result, String reasonCode, JsonNode output, List<Long> evidenceIds) {}
    record Detail(Run run, JsonNode facts, List<Check> checks, ComputeEvidenceService.Detail evidenceSnapshot) {}

    private static final String RUN_SELECT = """
            SELECT r.*, s.source_release_id, s.source_version_tag, s.evaluator_version, s.formula_version,
                   es.as_of AS snapshot_as_of
            FROM rule_evaluation_run r JOIN rule_set s ON s.id=r.rule_set_id
            LEFT JOIN evidence_snapshot es ON es.id=r.snapshot_id
            """;
    private final ComputeDatabase.Store store;
    private final ComputeRuleRunner runner;
    private final ObjectMapper json;
    private final ComputeEvidenceService evidence;

    ComputeRuleEvaluationService(ComputeDatabase.Store store, ComputeRuleRunner runner, ObjectMapper json, ComputeEvidenceService evidence) {
        this.store = store;
        this.runner = runner;
        this.json = json;
        this.evidence = evidence;
    }

    Detail execute(JsonNode body) {
        if (body == null || !body.isObject() || !body.path("stage").isTextual()) {
            throw new BizException("运行请求必须包含字符串 stage 和对象 facts");
        }
        String stage = body.path("stage").textValue();
        boolean fromServer = body.has("requestId") || body.has("nodeKey");
        if (body.has("snapshotId") || body.has("asOf")) throw new BizException("运行不能指定旧快照或服务端判定时间");
        if (fromServer) {
            if (!"ADMISSION".equals(stage)) throw new BizException("服务端证据目前仅支持 ADMISSION 阶段");
            ComputeEvidenceService.validateRequest(body);
        }
        JsonNode facts = body.path("facts");
        if (!fromServer) ComputeRuleRunner.validateInput(stage, facts);
        JdbcTemplate jdbc = store.jdbc();
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(
                Objects.requireNonNull(jdbc.getDataSource())));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return transaction.execute(status -> {
            var snapshot = runner.pinActive();
            Instant evaluatedAt = ComputeEvidenceService.now();
            var captured = fromServer ? evidence.capture(body, snapshot, evaluatedAt) : null;
            JsonNode input = fromServer ? ComputeEvidenceAdmission.facts(captured, json) : facts.deepCopy();
            var evaluation = runner.evaluate(snapshot, stage, input);
            if (fromServer) evaluation = ComputeEvidenceAdmission.guard(evaluation, captured);
            long id = ComputeReleaseBridge.insert(jdbc, """
                    INSERT INTO rule_evaluation_run (rule_set_id, stage, actor, evaluated_at, facts, overall_result,input_source,snapshot_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, snapshot.ruleSetId(), stage, Objects.requireNonNullElse(CurrentUser.username(), "system"),
                    LocalDateTime.ofInstant(evaluatedAt, ZoneOffset.UTC), input.toString(), evaluation.overallResult().name(),
                    fromServer ? "SERVER_SNAPSHOT" : "CALLER", fromServer ? captured.snapshot().id() : null);
            for (var check : evaluation.checks()) {
                jdbc.update("""
                        INSERT INTO rule_evaluation_check
                        (run_id, rule_set_id, rule_version_id, definition_version, result, reason_code, output)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, id, snapshot.ruleSetId(), check.ruleVersionId(), check.definitionVersion(),
                        check.result().name(), check.reasonCode(), serialize(check.output()));
                if (fromServer) for (long evidenceId : ComputeEvidenceAdmission.references(captured, check.ruleCode())) {
                    jdbc.update("""
                            INSERT INTO rule_evaluation_check_evidence(run_id,rule_version_id,evidence_id,snapshot_id)
                            VALUES(?,?,?,?)
                            """, id, check.ruleVersionId(), evidenceId, captured.snapshot().id());
                }
            }
            return detail(jdbc, id);
        });
    }

    PageResult<Run> list(int pageNum, int pageSize) {
        JdbcTemplate jdbc = store.jdbc();
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM rule_evaluation_run", Long.class);
        var runs = jdbc.query(RUN_SELECT + " ORDER BY r.id DESC LIMIT ? OFFSET ?",
                (row, index) -> readRun(row), pageSize, (pageNum - 1L) * pageSize);
        return PageResult.of(runs, total, pageNum, pageSize);
    }

    Detail detail(long id) {
        return detail(store.jdbc(), id);
    }

    private Detail detail(JdbcTemplate jdbc, long id) {
        return jdbc.query(RUN_SELECT + " WHERE r.id=?", rows -> {
            if (!rows.next()) {
                throw new BizException("规则运行记录不存在");
            }
            Run run = readRun(rows);
            JsonNode facts = read(rows.getString("facts"));
            var references = new java.util.HashMap<Long, List<Long>>();
            jdbc.query("SELECT rule_version_id,evidence_id FROM rule_evaluation_check_evidence WHERE run_id=? ORDER BY evidence_id",
                    (org.springframework.jdbc.core.RowCallbackHandler) row -> references.computeIfAbsent(row.getLong("rule_version_id"), key -> new java.util.ArrayList<>())
                            .add(row.getLong("evidence_id")), id);
            var checks = jdbc.query("""
                    SELECT c.*, v.rule_code, v.version FROM rule_evaluation_check c
                    JOIN rule_version v ON v.id=c.rule_version_id WHERE c.run_id=? ORDER BY v.rule_code
                    """, (row, index) -> new Check(row.getLong("rule_version_id"), row.getString("rule_code"),
                    row.getInt("version"), row.getInt("definition_version"), Status.valueOf(row.getString("result")),
                    row.getString("reason_code"), read(row.getString("output")),
                    List.copyOf(references.getOrDefault(row.getLong("rule_version_id"), List.of()))), id);
            return new Detail(run, facts, checks, run.snapshotId() == null ? null : evidence.detail(run.snapshotId()));
        }, id);
    }

    private Run readRun(ResultSet row) throws SQLException {
        return new Run(row.getLong("id"), row.getString("stage"), row.getLong("rule_set_id"),
                row.getLong("source_release_id"), row.getString("source_version_tag"), row.getString("evaluator_version"),
                row.getString("formula_version"), row.getString("actor"),
                row.getObject("evaluated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                row.getString("input_source"), Status.valueOf(row.getString("overall_result")), false,
                (Long) row.getObject("snapshot_id"), row.getObject("snapshot_as_of", LocalDateTime.class) == null ? null
                        : row.getObject("snapshot_as_of", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("运行记录 JSON 无法读取", e);
        }
    }

    private String serialize(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("规则结果无法保存", e);
        }
    }
}
