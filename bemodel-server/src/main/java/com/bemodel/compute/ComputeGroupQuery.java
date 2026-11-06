package com.bemodel.compute;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

@Service
@Profile("compute")
final class ComputeGroupQuery {
    record Observation(String source, Instant observedAt, Instant validUntil, String status,
                       Boolean healthy, Boolean occupied) {
        boolean usableAt(Instant at) {
            return "OK".equals(status) && observedAt != null && validUntil != null
                    && !at.isBefore(observedAt) && !at.isAfter(validUntil)
                    && healthy != null && occupied != null;
        }
    }

    record Gpu(String uuid, int index, long vramBytes, Observation observation) {}

    record Holder(String kind, long id, String name) {}

    record Reservation(long allocationId, long jobId, long requestId) {}

    record Group(String code, Holder holder, Reservation reservation, List<Gpu> cards,
                 boolean directlyAllocatable, List<String> reasons) {}

    private record GroupRow(String code, Holder holder, Reservation reservation, Gpu gpu) {}

    private static final String SELECT_GROUPS = """
            WITH observation_rank AS (
                SELECT o.*, ROW_NUMBER() OVER (
                    PARTITION BY o.subject_id ORDER BY o.observed_at DESC, o.id DESC
                ) AS observation_rank
                FROM resource_observation o
                JOIN compute_node n ON n.id = o.node_id
                WHERE o.subject_kind = 'GPU' AND n.node_key = ? AND o.observed_at <= ?
            )
            SELECT g.group_code, d.id AS deployment_id, d.name AS deployment_name,
                   h.allocation_id AS held_allocation_id,
                   p.id AS pending_id, pj.id AS pending_job_id, pj.request_id AS pending_request_id,
                   gpu.gpu_uuid, gpu.gpu_index, gpu.vram_bytes,
                   o.source, o.observed_at, o.valid_until, o.status AS observation_status,
                   JSON_UNQUOTE(JSON_EXTRACT(o.values_json, '$.healthy')) AS healthy,
                   JSON_UNQUOTE(JSON_EXTRACT(o.values_json, '$.occupied')) AS occupied
            FROM gpu_group g
            JOIN compute_node n ON n.id = g.node_id
            LEFT JOIN gpu_device gpu ON gpu.group_id = g.id
            LEFT JOIN hold_period h ON h.id = g.current_hold_id AND h.ended_at IS NULL
            LEFT JOIN deployment d ON d.id = h.deployment_id
            LEFT JOIN gpu_allocation p ON p.id = g.pending_allocation_id AND p.status = 'PENDING'
            LEFT JOIN compute_job pj ON pj.id = p.job_id
            LEFT JOIN observation_rank o ON o.subject_id = gpu.id AND o.observation_rank = 1
            WHERE n.node_key = ?
            ORDER BY g.id, gpu.gpu_index
            """;

    private final ComputeDatabase.Store store;

    ComputeGroupQuery(ComputeDatabase.Store store) {
        this.store = store;
    }

    List<Group> groups(String nodeKey, Instant evidenceAsOf) {
        List<GroupRow> rows = store.jdbc().query(SELECT_GROUPS, (rs, rowNum) -> readRow(rs),
                nodeKey, LocalDateTime.ofInstant(evidenceAsOf, ZoneOffset.UTC), nodeKey);
        var grouped = new LinkedHashMap<String, List<GroupRow>>();
        for (GroupRow row : rows) {
            grouped.computeIfAbsent(row.code(), key -> new ArrayList<>()).add(row);
        }
        return grouped.values().stream().map(group -> {
            GroupRow first = group.getFirst();
            var cards = group.stream().map(GroupRow::gpu).filter(java.util.Objects::nonNull).toList();
            return evaluate(first.code(), first.holder(), first.reservation(), cards, evidenceAsOf);
        }).toList();
    }

    static Group evaluate(String code, Holder holder, Reservation reservation, List<Gpu> cards, Instant at) {
        var reasons = new ArrayList<String>();
        if (holder != null) reasons.add("GROUP_HELD");
        if (reservation != null) reasons.add("GROUP_RESERVED");
        if (cards.isEmpty()) reasons.add("NO_GPU_REGISTERED");
        boolean missingEvidence = false;
        boolean unhealthy = false;
        boolean occupied = false;
        for (Gpu card : cards) {
            Observation observation = card.observation();
            if (observation == null || !observation.usableAt(at)) {
                missingEvidence = true;
                continue;
            }
            unhealthy |= !observation.healthy();
            occupied |= observation.occupied();
        }
        if (missingEvidence) reasons.add("GPU_EVIDENCE_UNAVAILABLE");
        if (unhealthy) reasons.add("GPU_UNHEALTHY");
        if (occupied) reasons.add("GPU_OCCUPIED");
        return new Group(code, holder, reservation, List.copyOf(cards), reasons.isEmpty(), List.copyOf(reasons));
    }

    private static GroupRow readRow(ResultSet rs) throws SQLException {
        Long deploymentId = rs.getObject("deployment_id", Long.class);
        Long allocationId = rs.getObject("held_allocation_id", Long.class);
        Holder holder = null;
        if (deploymentId != null) {
            holder = new Holder("DEPLOYMENT", deploymentId, rs.getString("deployment_name"));
        } else if (allocationId != null) {
            holder = new Holder("ALLOCATION", allocationId, null);
        }
        Long pendingId = rs.getObject("pending_id", Long.class);
        Reservation reservation = pendingId == null ? null :
                new Reservation(pendingId, rs.getLong("pending_job_id"), rs.getLong("pending_request_id"));
        String uuid = rs.getString("gpu_uuid");
        String source = rs.getString("source");
        Observation observation = source == null ? null : new Observation(source, instant(rs, "observed_at"),
                instant(rs, "valid_until"), rs.getString("observation_status"),
                readBoolean(rs.getString("healthy")), readBoolean(rs.getString("occupied")));
        Gpu gpu = uuid == null ? null : new Gpu(uuid, rs.getInt("gpu_index"), rs.getLong("vram_bytes"), observation);
        return new GroupRow(rs.getString("group_code"), holder, reservation, gpu);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime time = rs.getObject(column, LocalDateTime.class);
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }

    private static Boolean readBoolean(String value) {
        if ("true".equals(value) || "1".equals(value)) return true;
        if ("false".equals(value) || "0".equals(value)) return false;
        return null;
    }
}
