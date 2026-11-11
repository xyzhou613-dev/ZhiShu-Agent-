package com.bemodel.compute;

import com.bemodel.common.BizException;
import com.bemodel.common.PageResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

@Service
@Profile("compute")
final class ComputeRequestService {
    record Request(long id, long employeeId, long projectId, long taskTypeId, String clientRequestKey,
                   int requestedGpuCount, JsonNode requestedResourceSpec, JsonNode launchSpec,
                   String status, Instant submittedAt) {}

    record Job(long id, long requestId, long taskTypeId, int effectiveGpuCount, JsonNode effectiveResourceSpec,
               String status, Instant createdAt, Instant startedAt, Instant endedAt) {}

    record Detail(Request request, List<Job> jobs, Job currentJob) {}

    private record Submission(long employeeId, long projectId, long taskTypeId, String clientRequestKey,
                              int requestedGpuCount, JsonNode requestedResourceSpec, JsonNode launchSpec) {}

    private record StoredRequest(Request request, String fingerprint) {}

    private final ComputeDatabase.Store store;
    private final ObjectMapper json;

    ComputeRequestService(ComputeDatabase.Store store, ObjectMapper json) {
        this.store = store;
        this.json = json;
    }

    Request submit(JsonNode body) {
        Submission submission = validate(body);
        String fingerprint = fingerprint(submission);
        JdbcTemplate jdbc = store.jdbc();
        var existing = findByKey(jdbc, submission);
        if (existing != null) {
            return replay(existing, fingerprint);
        }
        try {
            return transaction(jdbc).execute(status -> {
                verifyReferences(jdbc, submission);
                var key = new GeneratedKeyHolder();
                jdbc.update(connection -> {
                    var statement = connection.prepareStatement("""
                            INSERT INTO compute_request (employee_id, project_id, task_type_id, client_request_key,
                                request_fingerprint, requested_gpu_count, requested_resource_spec, launch_spec, submitted_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
                            """, Statement.RETURN_GENERATED_KEYS);
                    statement.setLong(1, submission.employeeId());
                    statement.setLong(2, submission.projectId());
                    statement.setLong(3, submission.taskTypeId());
                    statement.setString(4, submission.clientRequestKey());
                    statement.setString(5, fingerprint);
                    statement.setInt(6, submission.requestedGpuCount());
                    statement.setString(7, submission.requestedResourceSpec().toString());
                    statement.setString(8, submission.launchSpec().toString());
                    return statement;
                }, key);
                return requireRequest(jdbc, Objects.requireNonNull(key.getKey()).longValue(), false);
            });
        } catch (DuplicateKeyException conflict) {
            // The failed insert has rolled back. Read the winning request outside its transaction snapshot.
            var winner = findByKey(jdbc, submission);
            if (winner == null) {
                throw conflict;
            }
            return replay(winner, fingerprint);
        }
    }

    PageResult<Request> list(int pageNum, int pageSize) {
        JdbcTemplate jdbc = store.jdbc();
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM compute_request", Long.class);
        var requests = jdbc.query("""
                SELECT * FROM compute_request ORDER BY submitted_at DESC, id DESC LIMIT ? OFFSET ?
                """, (row, index) -> readRequest(row), pageSize, (pageNum - 1L) * pageSize);
        return PageResult.of(requests, total, pageNum, pageSize);
    }

    Detail detail(long id) {
        JdbcTemplate jdbc = store.jdbc();
        var transaction = transaction(jdbc);
        transaction.setReadOnly(true);
        return transaction.execute(status -> {
            Request request = requireRequest(jdbc, id, false);
            var jobs = jdbc.query("SELECT * FROM compute_job WHERE request_id = ? ORDER BY id DESC",
                    (row, index) -> readJob(row), id);
            Long activeId = jdbc.query("SELECT job_id FROM active_job WHERE request_id = ?",
                    rows -> rows.next() ? rows.getLong("job_id") : null, id);
            Job current = jobs.stream().filter(job -> Objects.equals(job.id(), activeId)).findFirst().orElse(null);
            return new Detail(request, jobs, current);
        });
    }

    Request cancel(long id) {
        JdbcTemplate jdbc = store.jdbc();
        return transaction(jdbc).execute(status -> {
            Request request = requireRequest(jdbc, id, true);
            if ("CANCELLED".equals(request.status())) {
                return request;
            }
            if (!"SUBMITTED".equals(request.status()) || Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM compute_queue WHERE request_id = ?)
                        OR EXISTS(SELECT 1 FROM compute_job WHERE request_id = ?)
                        OR EXISTS(SELECT 1 FROM workflow_run WHERE request_id = ?)
                    """, Boolean.class, id, id, id))) {
                throw new BizException("仅可取消尚未进入调度的已提交申请");
            }
            jdbc.update("UPDATE compute_request SET status = 'CANCELLED' WHERE id = ?", id);
            return requireRequest(jdbc, id, false);
        });
    }

    Job retry(long requestId, long jobId) {
        JdbcTemplate jdbc = store.jdbc();
        return transaction(jdbc).execute(status -> {
            Request request = requireRequest(jdbc, requestId, true);
            Job source = jdbc.query("SELECT * FROM compute_job WHERE id = ? AND request_id = ? FOR UPDATE",
                    rows -> rows.next() ? readJob(rows) : null, jobId, requestId);
            if (source == null) {
                throw new BizException("申请下不存在该作业");
            }
            if (!"FAILED".equals(source.status()) || source.endedAt() == null) {
                throw new BizException("仅可重试已结束的失败作业");
            }

            // Attempts are appended under the request lock. The first later attempt is the replay result.
            Job successor = jdbc.query("""
                    SELECT * FROM compute_job WHERE request_id = ? AND id > ? ORDER BY id LIMIT 1
                    """, rows -> rows.next() ? readJob(rows) : null, requestId, jobId);
            if (successor != null) {
                return successor;
            }
            if (!"APPROVED".equals(request.status())) {
                throw new BizException("仅已批准的申请可重试作业");
            }
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM active_job WHERE request_id = ?)
                        OR EXISTS(SELECT 1 FROM workflow_run WHERE job_id = ?)
                        OR EXISTS(SELECT 1 FROM gpu_allocation WHERE job_id = ? AND status IN ('PENDING', 'OCCUPIED'))
                    """, Boolean.class, requestId, jobId, jobId))) {
                throw new BizException("申请仍有关联的活跃作业、执行流程或有效分配");
            }

            var key = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("""
                        INSERT INTO compute_job (request_id, task_type_id, effective_gpu_count,
                                                 effective_resource_spec, created_at)
                        SELECT request_id, task_type_id, effective_gpu_count, effective_resource_spec, UTC_TIMESTAMP(6)
                        FROM compute_job WHERE id = ?
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, jobId);
                return statement;
            }, key);
            long retryId = Objects.requireNonNull(key.getKey()).longValue();
            jdbc.update("INSERT INTO active_job (request_id, job_id) VALUES (?, ?)", requestId, retryId);
            return jdbc.queryForObject("SELECT * FROM compute_job WHERE id = ?", (row, index) -> readJob(row), retryId);
        });
    }

    private static TransactionTemplate transaction(JdbcTemplate jdbc) {
        return new TransactionTemplate(new DataSourceTransactionManager(
                Objects.requireNonNull(jdbc.getDataSource())));
    }

    private Request requireRequest(JdbcTemplate jdbc, long id, boolean lock) {
        return jdbc.query("SELECT * FROM compute_request WHERE id = ?" + (lock ? " FOR UPDATE" : ""),
                rows -> {
                    if (!rows.next()) {
                        throw new BizException("算力申请不存在");
                    }
                    return readRequest(rows);
                }, id);
    }

    private StoredRequest findByKey(JdbcTemplate jdbc, Submission submission) {
        return jdbc.query("SELECT * FROM compute_request WHERE employee_id = ? AND client_request_key = ?",
                rows -> rows.next() ? new StoredRequest(readRequest(rows), rows.getString("request_fingerprint")) : null,
                submission.employeeId(), submission.clientRequestKey());
    }

    private Request replay(StoredRequest existing, String fingerprint) {
        Request request = existing.request();
        if (!fingerprint.equals(existing.fingerprint()) && !fingerprint.equals(fingerprint(new Submission(
                request.employeeId(), request.projectId(), request.taskTypeId(), request.clientRequestKey(),
                request.requestedGpuCount(), request.requestedResourceSpec(), request.launchSpec())))) {
            throw new BizException("申请编号已用于不同的申请内容");
        }
        return request;
    }

    private static void verifyReferences(JdbcTemplate jdbc, Submission submission) {
        jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM employee WHERE id = ?) AS employee_exists,
                       EXISTS(SELECT 1 FROM project WHERE id = ?) AS project_exists,
                       EXISTS(SELECT 1 FROM task_type WHERE id = ?) AS task_type_exists
                """, (row, index) -> {
            if (!row.getBoolean("employee_exists")) {
                throw new BizException("员工不存在");
            }
            if (!row.getBoolean("project_exists")) {
                throw new BizException("项目不存在");
            }
            if (!row.getBoolean("task_type_exists")) {
                throw new BizException("任务类型不存在");
            }
            return null;
        }, submission.employeeId(), submission.projectId(), submission.taskTypeId());
    }

    private static Submission validate(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new BizException("申请内容必须是 JSON 对象");
        }
        long employee = positiveLong(body.path("employeeId"), "employeeId");
        long project = positiveLong(body.path("projectId"), "projectId");
        long taskType = positiveLong(body.path("taskTypeId"), "taskTypeId");
        JsonNode key = body.path("clientRequestKey");
        String requestKey = key.isTextual() ? key.textValue().strip() : "";
        if (requestKey.isEmpty() || requestKey.codePointCount(0, requestKey.length()) > 128) {
            throw new BizException("clientRequestKey 不能为空且最多 128 字符");
        }
        long cardCount = positiveLong(body.path("requestedGpuCount"), "requestedGpuCount");
        if (cardCount > Short.MAX_VALUE) {
            throw new BizException("requestedGpuCount 不能超过 32767");
        }
        JsonNode resources = body.path("requestedResourceSpec");
        if (!resources.isObject()) {
            throw new BizException("requestedResourceSpec 必须是 JSON 对象");
        }
        for (String field : List.of("vramBytesPerGpu", "memoryBytes", "temporaryBytes", "persistentBytes")) {
            JsonNode amount = resources.get(field);
            if (amount != null && (!amount.isIntegralNumber() || !amount.canConvertToLong() || amount.longValue() < 0)) {
                throw new BizException(field + " 必须是非负整数字节值");
            }
        }
        JsonNode interconnect = resources.get("gpuInterconnect");
        if (interconnect != null && !nonBlankText(interconnect)) {
            throw new BizException("gpuInterconnect 必须是非空字符串");
        }
        JsonNode launch = body.path("launchSpec");
        if (!launch.isObject() || !nonBlankText(launch.path("imageRef"))) {
            throw new BizException("launchSpec.imageRef 必须是非空字符串");
        }
        validateArray(launch.get("commandArgs"), "launchSpec.commandArgs", JsonNode::isTextual);
        validateArray(launch.get("mounts"), "launchSpec.mounts", JsonNode::isObject);
        return new Submission(employee, project, taskType, requestKey, (int) cardCount, resources, launch);
    }

    private static boolean nonBlankText(JsonNode value) {
        return value.isTextual() && !value.textValue().isBlank();
    }

    private static void validateArray(JsonNode value, String field, java.util.function.Predicate<JsonNode> element) {
        if (value == null) {
            return;
        }
        if (!value.isArray()) {
            throw new BizException(field + " 必须是 JSON 数组");
        }
        for (JsonNode item : value) {
            if (!element.test(item)) {
                throw new BizException(field + " 含有类型不正确的元素");
            }
        }
    }

    private static long positiveLong(JsonNode value, String field) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw new BizException(field + " 必须是正整数");
        }
        return value.longValue();
    }

    private String fingerprint(Submission submission) {
        var content = json.createArrayNode().add(submission.employeeId()).add(submission.projectId())
                .add(submission.taskTypeId()).add(submission.requestedGpuCount())
                .add(canonical(submission.requestedResourceSpec())).add(canonical(submission.launchSpec()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsBytes(content)));
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException("无法计算申请指纹", e);
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            var ordered = new TreeMap<String, JsonNode>();
            value.fields().forEachRemaining(field -> ordered.put(field.getKey(), canonical(field.getValue())));
            return json.createObjectNode().setAll(ordered);
        }
        if (value.isArray()) {
            var array = json.createArrayNode();
            value.forEach(item -> array.add(canonical(item)));
            return array;
        }
        return value;
    }

    private Request readRequest(ResultSet row) throws SQLException {
        return new Request(row.getLong("id"), row.getLong("employee_id"), row.getLong("project_id"),
                row.getLong("task_type_id"), row.getString("client_request_key"), row.getInt("requested_gpu_count"),
                readJson(row, "requested_resource_spec"), readJson(row, "launch_spec"), row.getString("status"),
                readInstant(row, "submitted_at"));
    }

    private Job readJob(ResultSet row) throws SQLException {
        return new Job(row.getLong("id"), row.getLong("request_id"), row.getLong("task_type_id"),
                row.getInt("effective_gpu_count"), readJson(row, "effective_resource_spec"), row.getString("status"),
                readInstant(row, "created_at"), readInstant(row, "started_at"), readInstant(row, "ended_at"));
    }

    private JsonNode readJson(ResultSet row, String column) throws SQLException {
        try {
            return json.readTree(row.getString(column));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("算力记录 JSON 无法读取", e);
        }
    }

    private static Instant readInstant(ResultSet row, String column) throws SQLException {
        LocalDateTime timestamp = row.getObject(column, LocalDateTime.class);
        return timestamp == null ? null : timestamp.toInstant(ZoneOffset.UTC);
    }
}
