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
            return usableAt(at,30);
        }
        boolean usableAt(Instant at,long maxAge) {
            return "OK".equals(status) && observedAt != null && validUntil != null
                    && ComputeResourceMath.evidenceValid(observedAt,validUntil,at,maxAge)
                    && healthy != null && occupied != null;
        }
    }

    record Gpu(String uuid, int index, long vramBytes, Observation observation) {}

    record Holder(String kind, long id, String name) {}

    record Reservation(long allocationId, long jobId, long requestId) {}
    record Usage(String basis,List<String> usedGpuUuids,List<String> retainedGpuUuids) {}
    record RequestRef(long requestId,long jobId,long employeeId,long projectId) {}
    record RestoreResponsibility(long deploymentId,long revisionId,int revision,long ownerEmployeeId,Long workflowId,String workflowStatus) {}

    record Group(String code, Holder holder, Reservation reservation, List<Gpu> cards,
                 boolean directlyAllocatable, List<String> reasons,long groupId,boolean ledgerAvailable,
                 boolean ledgerConsistent,long maxObservationAgeSeconds,Usage holderUsage,Usage pendingUsage,
                 RequestRef holderRequest,RequestRef pendingRequest,RestoreResponsibility restoreResponsibility) {}

    private record GroupRow(long groupId,String code, Holder holder, Reservation reservation, Gpu gpu,
                            boolean heldGpuUsed,boolean pendingGpuUsed,boolean ledgerConsistent,
                            RequestRef holderRequest,RequestRef pendingRequest,RestoreResponsibility restore) {}

    private static final String SELECT_GROUPS = """
            WITH observation_rank AS (
                SELECT o.*, ROW_NUMBER() OVER (
                    PARTITION BY o.subject_id ORDER BY o.observed_at DESC, o.id DESC
                ) AS observation_rank
                FROM resource_observation o
                JOIN compute_node n ON n.id = o.node_id
                WHERE o.subject_kind = 'GPU' AND n.node_key = ? AND o.observed_at <= ?
            )
            SELECT g.id AS group_id,g.group_code, d.id AS deployment_id, d.name AS deployment_name,
                   h.allocation_id AS held_allocation_id,
                   ((g.current_hold_id <=> h.id) AND (g.pending_allocation_id <=> p.id)) AS ledger_consistent,
                   hj.id AS held_job_id,hr.id AS held_request_id,hr.employee_id AS held_employee_id,hr.project_id AS held_project_id,
                   pr.employee_id AS pending_employee_id,pr.project_id AS pending_project_id,
                   rr.id AS restore_revision_id,rr.version AS restore_revision_version,rr.deployment_id AS restore_deployment_id,
                   rd.owner_employee_id AS restore_owner,w.id AS workflow_id,w.status AS workflow_status,
                   EXISTS(SELECT 1 FROM allocation_gpu ag WHERE ag.allocation_id=h.allocation_id AND ag.gpu_id=gpu.id) AS held_gpu_used,
                   EXISTS(SELECT 1 FROM allocation_gpu ag WHERE ag.allocation_id=p.id AND ag.gpu_id=gpu.id) AS pending_gpu_used,
                   p.id AS pending_id, pj.id AS pending_job_id, pj.request_id AS pending_request_id,
                   gpu.gpu_uuid, gpu.gpu_index, gpu.vram_bytes,
                   o.source, o.observed_at, o.valid_until, o.status AS observation_status,
                   JSON_UNQUOTE(JSON_EXTRACT(o.values_json, '$.healthy')) AS healthy,
                   JSON_UNQUOTE(JSON_EXTRACT(o.values_json, '$.occupied')) AS occupied
            FROM gpu_group g
            JOIN compute_node n ON n.id = g.node_id
            LEFT JOIN gpu_device gpu ON gpu.group_id = g.id
            LEFT JOIN hold_period h ON h.group_id = g.id AND h.ended_at IS NULL
            LEFT JOIN deployment d ON d.id = h.deployment_id
            LEFT JOIN gpu_allocation a ON a.id=h.allocation_id
            LEFT JOIN compute_job hj ON hj.id=a.job_id
            LEFT JOIN compute_request hr ON hr.id=hj.request_id
            LEFT JOIN gpu_allocation p ON p.group_id=g.id AND p.status = 'PENDING'
            LEFT JOIN compute_job pj ON pj.id = p.job_id
            LEFT JOIN compute_request pr ON pr.id=pj.request_id
            LEFT JOIN workflow_run w ON w.id=(SELECT w2.id FROM workflow_run w2 WHERE w2.group_id=g.id
                AND w2.job_id=COALESCE(hj.id,pj.id) ORDER BY w2.id DESC LIMIT 1)
            LEFT JOIN deployment_revision rr ON rr.id=COALESCE(w.restore_revision_id,h.deployment_revision_id)
            LEFT JOIN deployment rd ON rd.id=rr.deployment_id
            LEFT JOIN observation_rank o ON o.subject_id = gpu.id AND o.observation_rank = 1
            WHERE n.node_key = ?
            ORDER BY g.id, gpu.gpu_index
            """;

    private final ComputeDatabase.Store store;

    ComputeGroupQuery(ComputeDatabase.Store store) {
        this.store = store;
    }

    List<Group> groups(String nodeKey, Instant evidenceAsOf) {
        var tx=store.transactions();
        tx.setReadOnly(true);
        return tx.execute(status->readGroups(nodeKey,evidenceAsOf));
    }
    private List<Group> readGroups(String nodeKey,Instant evidenceAsOf) {
        var ages=store.jdbc().queryForList("SELECT JSON_UNQUOTE(JSON_EXTRACT(v.definition,'$.parameters.maxAgeSeconds')) AS max_age FROM active_rule_set p JOIN rule_set_member m ON m.rule_set_id=p.rule_set_id AND m.rule_code='R-11' JOIN rule_version v ON v.id=m.rule_version_id WHERE p.id=1");
        long maxAge=ages.isEmpty()?30:Long.parseLong(ages.getFirst().get("max_age").toString());
        List<GroupRow> rows = store.jdbc().query(SELECT_GROUPS, (rs, rowNum) -> readRow(rs),
                nodeKey, LocalDateTime.ofInstant(evidenceAsOf, ZoneOffset.UTC), nodeKey);
        var grouped = new LinkedHashMap<String, List<GroupRow>>();
        for (GroupRow row : rows) {
            grouped.computeIfAbsent(row.code(), key -> new ArrayList<>()).add(row);
        }
        return grouped.values().stream().map(group -> {
            GroupRow first = group.getFirst();
            var cards = group.stream().map(GroupRow::gpu).filter(java.util.Objects::nonNull).toList();
            var evaluated=evaluate(first.code(), first.holder(), first.reservation(), cards, evidenceAsOf,maxAge);
            var reasons=new ArrayList<>(evaluated.reasons());
            if(!first.ledgerConsistent()) reasons.add("GROUP_LEDGER_MISMATCH");
            var all=cards.stream().map(Gpu::uuid).toList();
            var heldUsed=group.stream().filter(row->row.gpu()!=null && (row.heldGpuUsed() || first.holder()!=null && "DEPLOYMENT".equals(first.holder().kind()))).map(row->row.gpu().uuid()).toList();
            var pendingUsed=group.stream().filter(row->row.gpu()!=null && row.pendingGpuUsed()).map(row->row.gpu().uuid()).toList();
            return new Group(first.code(),first.holder(),first.reservation(),cards,reasons.isEmpty(),List.copyOf(reasons),first.groupId(),
                ComputeResourceMath.groupAvailable(first.holder()!=null,first.reservation()!=null),first.ledgerConsistent(),maxAge,
                usage(first.holder()==null?"NONE":"DEPLOYMENT".equals(first.holder().kind())?"DEPLOYMENT_GROUP_BASELINE":"ALLOCATION_GPU",all,heldUsed),
                usage(first.reservation()==null?"NONE":"PENDING_ALLOCATION_GPU",all,pendingUsed),first.holderRequest(),first.pendingRequest(),first.restore());
        }).toList();
    }
    private static Usage usage(String basis,List<String> all,List<String> used) {
        return new Usage(basis,List.copyOf(used),"NONE".equals(basis)||all.isEmpty()?List.of():ComputeResourceMath.reservedCards(all,used));
    }

    static Group evaluate(String code, Holder holder, Reservation reservation, List<Gpu> cards, Instant at) {
        return evaluate(code,holder,reservation,cards,at,30);
    }
    static Group evaluate(String code, Holder holder, Reservation reservation, List<Gpu> cards, Instant at,long maxAge) {
        var reasons = new ArrayList<String>();
        if (holder != null) reasons.add("GROUP_HELD");
        if (reservation != null) reasons.add("GROUP_RESERVED");
        if (cards.isEmpty()) reasons.add("NO_GPU_REGISTERED");
        boolean missingEvidence = false;
        boolean unhealthy = false;
        boolean occupied = false;
        for (Gpu card : cards) {
            Observation observation = card.observation();
            if (observation == null || !observation.usableAt(at,maxAge)) {
                missingEvidence = true;
                continue;
            }
            unhealthy |= !observation.healthy();
            occupied |= observation.occupied();
        }
        if (missingEvidence) reasons.add("GPU_EVIDENCE_UNAVAILABLE");
        if (unhealthy) reasons.add("GPU_UNHEALTHY");
        if (occupied) reasons.add("GPU_OCCUPIED");
        return new Group(code, holder, reservation, List.copyOf(cards), reasons.isEmpty(), List.copyOf(reasons),0,
                ComputeResourceMath.groupAvailable(holder!=null,reservation!=null),true,maxAge,new Usage("NONE",List.of(),List.of()),new Usage("NONE",List.of(),List.of()),null,null,null);
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
        Long heldRequest=rs.getObject("held_request_id",Long.class);
        RequestRef holderRequest=heldRequest==null?null:new RequestRef(heldRequest,rs.getLong("held_job_id"),rs.getLong("held_employee_id"),rs.getLong("held_project_id"));
        RequestRef pendingRequest=pendingId==null?null:new RequestRef(rs.getLong("pending_request_id"),rs.getLong("pending_job_id"),rs.getLong("pending_employee_id"),rs.getLong("pending_project_id"));
        Long revision=rs.getObject("restore_revision_id",Long.class);
        RestoreResponsibility restore=revision==null?null:new RestoreResponsibility(rs.getLong("restore_deployment_id"),revision,rs.getInt("restore_revision_version"),rs.getLong("restore_owner"),rs.getObject("workflow_id",Long.class),rs.getString("workflow_status"));
        return new GroupRow(rs.getLong("group_id"),rs.getString("group_code"), holder, reservation, gpu,rs.getBoolean("held_gpu_used"),rs.getBoolean("pending_gpu_used"),rs.getBoolean("ledger_consistent"),holderRequest,pendingRequest,restore);
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
