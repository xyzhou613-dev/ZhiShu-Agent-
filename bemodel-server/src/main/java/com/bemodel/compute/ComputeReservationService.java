package com.bemodel.compute;

import com.bemodel.common.BizException;
import com.bemodel.common.PageResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import static com.bemodel.compute.ComputeRuleEvaluator.Status;

@Service @Profile("compute")
final class ComputeReservationService {
    record Receipt(long id,long requestId,long jobId,long allocationId,long groupId,long nodeId,String allocationStatus,
                   long ruleSetId,long snapshotId,long projectionRunId,long admissionDecisionId,long submissionDecisionId,
                   Long restoreRevisionId,Instant asOf,String actor) {}
    record Outcome(String outcome,String reasonCode,Long allocationId,String allocationStatus,Receipt receipt,
                   Long projectionRunId,ComputeQueueService.Entry queue,boolean authorizesExecution) {}
    record Detail(Receipt receipt,List<Map<String,Object>> decisions,List<Map<String,Object>> checks) {}
    private static final String SELECT="""
            SELECT r.*,a.status AS allocation_status,s.as_of FROM compute_reservation_receipt r
            JOIN gpu_allocation a ON a.id=r.allocation_id JOIN evidence_snapshot s ON s.id=r.snapshot_id
            """;
    private final ComputeDatabase.Store store;private final ComputeQueueService queue;
    private final ComputeRuleRunner runner;private final ComputeCapacityProjectionService projections;
    private final ObjectMapper json;private final ApplicationEventPublisher events;
    ComputeReservationService(ComputeDatabase.Store store,ComputeQueueService queue,ComputeRuleRunner runner,
                              ComputeCapacityProjectionService projections,ObjectMapper json,ApplicationEventPublisher events){
        this.store=store;this.queue=queue;this.runner=runner;this.projections=projections;this.json=json;this.events=events;
    }
    Outcome reserve(JsonNode body){
        ComputeEvidenceService.validateRequest(body);
        body.fieldNames().forEachRemaining(k->{if(!List.of("nodeKey","requestId").contains(k))throw new BizException("预留仅接受 nodeKey 和 requestId，规格与暂停方案由服务端确定");});
        var tx=store.transactions();tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        Outcome outcome;
        try{outcome=tx.execute(status->reserveLocked(body));}
        catch(DuplicateKeyException e){throw new BizException("资源预留发生并发冲突，请重评后重试");}
        if("RESERVED".equals(outcome.outcome()))events.publishEvent(new ComputeQueueScheduler.ResourceChanged(body.path("nodeKey").asText()));
        return outcome;
    }

    private Outcome reserveLocked(JsonNode body){
        JdbcTemplate j=store.jdbc();long requestId=body.path("requestId").asLong();String nodeKey=body.path("nodeKey").asText();
        long nodeId=ComputeSchedulingLocks.node(j,nodeKey);
        var ids=new TreeSet<>(j.queryForList("SELECT request_id FROM compute_queue WHERE node_id=? AND status='WAITING'",Long.class,nodeId));ids.add(requestId);
        for(long id:ids)j.queryForList("SELECT id FROM compute_request WHERE id=? FOR UPDATE",Long.class,id);
        var requests=j.queryForList("SELECT * FROM compute_request WHERE id=? FOR UPDATE",requestId);
        if(requests.isEmpty())throw new BizException("算力申请不存在");var request=requests.getFirst();
        var jobs=j.queryForList("SELECT b.* FROM active_job a JOIN compute_job b ON b.id=a.job_id WHERE a.request_id=? FOR UPDATE",requestId);
        Long jobId=jobs.isEmpty()?null:((Number)jobs.getFirst().get("id")).longValue();
        if(jobId!=null){
            var allocated=j.queryForList("SELECT a.*,g.node_id FROM gpu_allocation a JOIN gpu_group g ON g.id=a.group_id WHERE a.job_id=? FOR UPDATE",jobId);
            if(!allocated.isEmpty()){
                var a=allocated.getFirst();if(((Number)a.get("node_id")).longValue()!=nodeId)throw new BizException("申请分配属于其他节点");
                var receipts=j.query(SELECT+" WHERE r.allocation_id=?",(r,i)->receipt(r),a.get("id"));
                String state=a.get("status").toString();boolean live=List.of("PENDING","OCCUPIED").contains(state);
                return new Outcome(live?"ALREADY_RESERVED":"HISTORICAL_RESERVATION",receipts.isEmpty()?"LEGACY_ALLOCATION":"REPLAYED_RESERVATION",((Number)a.get("id")).longValue(),state,receipts.isEmpty()?null:receipts.getFirst(),null,optionalQueue(requestId),false);
            }
        }
        if(!List.of("SUBMITTED","APPROVED").contains(request.get("status")))throw new BizException("申请不处于可调度状态");
        var entry=queue.detail(requestId);
        if(entry.nodeId()==null || entry.nodeId()!=nodeId || !"WAITING".equals(entry.status()))throw new BizException("请先加入该节点的待调度队列");
        if(jobId!=null && !"PENDING".equals(jobs.getFirst().get("status")) || jobId==null&&j.queryForObject("SELECT COUNT(*) FROM compute_job WHERE request_id=?",Integer.class,requestId)>0){
            j.update("UPDATE compute_queue SET status='CANCELLED',reason_code='NEEDS_PENDING_JOB',next_evaluate_at=NULL WHERE request_id=?",requestId);
            return waiting(requestId,"NEEDS_PENDING_JOB",null);
        }
        queue.refreshLocked(nodeKey,nodeId);entry=queue.detail(requestId);
        if(entry.targetGroupId()==null)return waiting(requestId,"QUEUE_NO_TARGET",null);
        long groupId=entry.targetGroupId();var rules=runner.pinActive();
        var queueRows=j.queryForList("""
                SELECT request_id,priority,DATE_FORMAT(submitted_at,'%Y-%m-%dT%H:%i:%s.%fZ') AS submitted_at
                FROM compute_queue WHERE node_id=? AND target_group_id=? AND status='WAITING'
                ORDER BY priority DESC,submitted_at,request_id FOR UPDATE
                """,nodeId,groupId);
        var headFacts=json.createObjectNode().put("requestId",requestId);var candidates=headFacts.putArray("queue");
        for(var row:queueRows)candidates.addObject().put("requestId",((Number)row.get("request_id")).longValue()).put("priority",((Number)row.get("priority")).longValue()).put("submittedAt",row.get("submitted_at").toString());
        var head=evaluate(rules,"R-12",headFacts);if(head.result()!=Status.PASS)return waiting(requestId,"QUEUE_NOT_HEAD",null);
        var group=j.queryForMap("SELECT * FROM gpu_group WHERE id=? FOR UPDATE",groupId);
        var holds=j.queryForList("SELECT * FROM hold_period WHERE group_id=? AND ended_at IS NULL FOR UPDATE",groupId);
        var pending=j.queryForList("SELECT id FROM gpu_allocation WHERE group_id=? AND status='PENDING' FOR UPDATE",Long.class,groupId);
        if(!pending.isEmpty()||group.get("pending_allocation_id")!=null)return waiting(requestId,"QUEUE_CONFLICT",null);
        boolean consistent=holds.isEmpty()?group.get("current_hold_id")==null:holds.size()==1&&Objects.equals(group.get("current_hold_id"),holds.getFirst().get("id"));
        if(!consistent)return waiting(requestId,"GROUP_LEDGER_MISMATCH",null);
        if(!holds.isEmpty()&&holds.getFirst().get("allocation_id")!=null)return waiting(requestId,"GROUP_HELD",null);
        if(!j.queryForList("SELECT id FROM gpu_allocation WHERE group_id=? AND status='OCCUPIED' FOR UPDATE",Long.class,groupId).isEmpty())return waiting(requestId,"GROUP_LEDGER_MISMATCH",null);
        var proposal=(ObjectNode)body.deepCopy();proposal.put("mode","PLAN");Long restoreRevision=null;
        if(!holds.isEmpty()){
            proposal.put("pausedDeploymentId",((Number)holds.getFirst().get("deployment_id")).longValue());
            restoreRevision=(Long)holds.getFirst().get("deployment_revision_id");
            if(restoreRevision==null) return waiting(requestId,"MISSING_RESTORE_REVISION",null);
        }
        var projected=projections.project(proposal);var captured=projected.evidenceSnapshot();
        if(projected.run().ruleSetId()!=rules.ruleSetId() || !Objects.equals(projected.run().targetGroupId(),groupId))throw new BizException("预留过程中规则集合或目标不一致，请重评");
        var projectedChecks=new ArrayList<ComputeCapacityProjector.Check>();
        for(var c:projected.checks())projectedChecks.add(new ComputeCapacityProjector.Check(rules.rules().stream().filter(r->r.ruleVersionId()==c.ruleVersionId()).findFirst().orElseThrow(),Status.valueOf(c.result()),c.reasonCode(),c.facts(),c.output()));
        var admissionFacts=ComputeEvidenceAdmission.facts(captured,json);
        var ledger=captured.evidence().getFirst().values();
        if(ledger.path("activeJob").isObject()){
            var active=ledger.path("activeJob");var f=(ObjectNode)admissionFacts.path("R-01");f.set("gpuCount",active.path("effective_gpu_count"));
            var spec=(ObjectNode)active.path("effective_resource_spec").deepCopy();if(!spec.has("temporaryBytes"))spec.put("temporaryBytes",0);if(!spec.has("persistentBytes"))spec.put("persistentBytes",0);f.set("resourceSpec",spec);
        }
        var specCheck=evaluate(rules,"R-01",admissionFacts.path("R-01"));
        if(specCheck.result()!=Status.PASS)return waiting(requestId,specCheck.reasonCode(),projected.run().id());
        for(var check:projectedChecks)if(check.result()==Status.ERROR||check.result()==Status.FAIL)return waiting(requestId,check.reasonCode(),projected.run().id());
        if(holds.isEmpty())for(var evidence:captured.evidence())if(evidence.observationId()!=null&&"GPU".equals(evidence.values().path("subjectKind").asText())
                && evidence.values().path("occupied").asBoolean() && ledger.path("cards").findValues("id").stream().anyMatch(id->id.asLong()==evidence.values().path("subjectId").asLong())){
            boolean inGroup=false;for(JsonNode c:ledger.path("cards"))if(c.path("id").asLong()==evidence.values().path("subjectId").asLong()&&c.path("group_id").asLong()==groupId)inGroup=true;
            if(inGroup)return waiting(requestId,"UNKNOWN_GPU_OCCUPANCY",projected.run().id());
        }
        if(!freshNow(captured))return waiting(requestId,"QUEUE_STALE_EVIDENCE",projected.run().id());
        if(jobId==null){
            jobId=ComputeReleaseBridge.insert(j,"""
                    INSERT INTO compute_job(request_id,task_type_id,effective_gpu_count,effective_resource_spec,created_at)
                    SELECT id,task_type_id,requested_gpu_count,requested_resource_spec,? FROM compute_request WHERE id=?
                    """,utc(captured.snapshot().asOf()),requestId);
            j.update("INSERT INTO active_job(request_id,job_id) VALUES(?,?)",requestId,jobId);
        }
        var plan=json.createObjectNode().put("jobId",jobId).put("targetGroupId",groupId).put("projectionRunId",projected.run().id()).put("ruleSetId",rules.ruleSetId()).put("actor",captured.snapshot().actor());
        if(restoreRevision!=null)plan.put("restoreRevisionId",restoreRevision);
        plan.set("projection",projected.output());
        var admission=new ArrayList<ComputeCapacityProjector.Check>();admission.add(specCheck);
        admission.add(projectedChecks.stream().filter(c->"R-11".equals(c.rule().ruleCode())).findFirst().orElseThrow());
        admission.add(audit(rules,admission,captured));
        long admissionId=decision(requestId,"ADMISSION",null,plan,admission,captured,rules);
        var bytes=projected.output().path("newReservationBytes");
        long allocationId=ComputeReleaseBridge.insert(j,"""
                INSERT INTO gpu_allocation(job_id,group_id,status,memory_bytes,temporary_bytes,persistent_bytes,reserved_at)
                VALUES(?,?,'PENDING',?,?,?,?)
                """,jobId,groupId,bytes.path("memoryBytes").asLong(),bytes.path("temporaryBytes").asLong(),bytes.path("persistentBytes").asLong(),utc(captured.snapshot().asOf()));
        for(JsonNode id:projected.output().path("usedGpuIds"))j.update("INSERT INTO allocation_gpu(allocation_id,gpu_id) VALUES(?,?)",allocationId,id.asLong());
        if(j.update("UPDATE gpu_group SET pending_allocation_id=? WHERE id=? AND pending_allocation_id IS NULL",allocationId,groupId)!=1)throw new BizException("QUEUE_CONFLICT");
        var receiptFacts=json.createObjectNode().put("duplicateRequest",false).put("transactionCommitted",true).put("nodeAndGroupLocked",true)
                .put("queueHeadRechecked",true).put("capacityRechecked",true).put("allocationId",allocationId)
                .put("pendingAllocationCount",j.queryForObject("SELECT COUNT(*) FROM gpu_allocation WHERE group_id=? AND status='PENDING'",Integer.class,groupId))
                .put("openHoldCount",holds.size());
        var reserved=evaluate(rules,"R-07",receiptFacts);if(reserved.result()!=Status.PASS)throw new BizException("预留回执核验失败");
        projectedChecks.add(head);projectedChecks.add(reserved);
        if(projectedChecks.stream().noneMatch(c->"R-13".equals(c.rule().ruleCode())))projectedChecks.add(evaluate(rules,"R-13",json.createObjectNode().put("preemptionRequired",false)));
        projectedChecks.add(audit(rules,projectedChecks,captured));plan.put("allocationId",allocationId);
        long submissionId=decision(requestId,"SUBMISSION",admissionId,plan,projectedChecks,captured,rules);
        j.update("UPDATE compute_request SET status='APPROVED' WHERE id=?",requestId);
        j.update("UPDATE compute_queue SET status='RESERVED',reason_code='RESERVED',next_evaluate_at=NULL,updated_at=? WHERE request_id=?",utc(ComputeEvidenceService.now()),requestId);
        long receiptId=ComputeReleaseBridge.insert(j,"""
                INSERT INTO compute_reservation_receipt(request_id,job_id,allocation_id,group_id,node_id,rule_set_id,snapshot_id,projection_run_id,admission_decision_id,submission_decision_id,restore_revision_id,actor)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """,requestId,jobId,allocationId,groupId,nodeId,rules.ruleSetId(),captured.snapshot().id(),projected.run().id(),admissionId,submissionId,restoreRevision,captured.snapshot().actor());
        var committed=new Outcome("RESERVED","PASS",allocationId,"PENDING",get(receiptId),projected.run().id(),queue.detail(requestId),false);
        if(!freshNow(captured))throw new BizException("证据在提交前过期，请重评后重试");
        return committed;
    }

    private long decision(long request,String kind,Long previous,JsonNode plan,List<ComputeCapacityProjector.Check> checks,ComputeEvidenceService.Detail captured,ComputeRuleRunner.Snapshot rules){
        var j=store.jdbc();var old=j.queryForList("SELECT d.id,d.as_of FROM current_decision c JOIN decision d ON d.id=c.decision_id WHERE c.request_id=? AND c.decision_kind=? FOR UPDATE",request,kind);
        Long supersedes=old.isEmpty()?null:((Number)old.getFirst().get("id")).longValue();
        if(!old.isEmpty()&&!captured.snapshot().asOf().isAfter(((LocalDateTime)old.getFirst().get("as_of")).toInstant(ZoneOffset.UTC)))throw new BizException("既有决策时间不早于当前快照，请核对时钟");
        long id=ComputeReleaseBridge.insert(j,"""
                INSERT INTO decision(request_id,kind,outcome,reason_code,as_of,snapshot_id,target_kind,target_id,plan,previous_decision_id,supersedes_decision_id,rule_set_id)
                VALUES(?,?,'APPROVED','PASS',?,?,'GPU_GROUP',?,?,?,?,?)
                """,request,kind,utc(captured.snapshot().asOf()),captured.snapshot().id(),plan.path("targetGroupId").asLong(),plan.toString(),previous,supersedes,rules.ruleSetId());
        for(var c:checks){
            long checkId=ComputeReleaseBridge.insert(j,"INSERT INTO rule_check(decision_id,rule_version_id,rule_set_id,definition_version,result,reason_code,facts,output,checked_at) VALUES(?,?,?,?,?,?,?,?,?)",id,c.rule().ruleVersionId(),rules.ruleSetId(),c.rule().definitionVersion(),c.result().name(),c.reasonCode(),c.facts().toString(),c.output().toString(),utc(captured.snapshot().asOf()));
            for(var e:captured.evidence())j.update("INSERT INTO rule_check_evidence(rule_check_id,evidence_id) VALUES(?,?)",checkId,e.id());
        }
        j.update("INSERT INTO current_decision(request_id,decision_kind,decision_id) VALUES(?,?,?) ON DUPLICATE KEY UPDATE decision_id=VALUES(decision_id)",request,kind,id);return id;
    }
    private ComputeCapacityProjector.Check audit(ComputeRuleRunner.Snapshot rules,List<ComputeCapacityProjector.Check> checks,ComputeEvidenceService.Detail captured){
        var f=json.createObjectNode().put("snapshotId",Long.toString(captured.snapshot().id())).put("asOf",captured.snapshot().asOf().toString());var sources=f.putArray("evidence");
        for(var e:captured.evidence())sources.addObject().put("evidenceId",Long.toString(e.id())).put("snapshotId",Long.toString(captured.snapshot().id())).put("source",e.source()).put("observedAt",e.observedAt().toString()).put("validUntil",e.validUntil().toString());
        var listed=f.putArray("checks");for(var c:checks){var row=listed.addObject().put("ruleCode",c.rule().ruleCode()).put("ruleVersion",c.rule().ruleVersion()).put("result",c.result().name());var refs=row.putArray("evidenceIds");for(var e:captured.evidence())refs.add(Long.toString(e.id()));}
        var audit=evaluate(rules,"R-10",f);if(audit.result()!=Status.PASS)throw new BizException("决策证据链核验失败");return audit;
    }
    private ComputeCapacityProjector.Check evaluate(ComputeRuleRunner.Snapshot rules,String code,JsonNode facts){var r=rules.rules().stream().filter(v->code.equals(v.ruleCode())).findFirst().orElseThrow();var result=new ComputeRuleEvaluator().evaluateSnapshot(code,r.ruleVersion(),r.definition(),facts);return new ComputeCapacityProjector.Check(r,result.result(),result.reasonCode(),facts,json.valueToTree(result.output()));}
    private boolean freshNow(ComputeEvidenceService.Detail captured){Instant now=Instant.now();return captured.evidence().stream().filter(e->e.observationId()!=null).allMatch(e->e.values().path("usable").asBoolean()&&ComputeResourceMath.evidenceValid(e.observedAt(),e.validUntil(),now,e.values().path("maxAgeSeconds").asLong()));}
    private Outcome waiting(long request,String reason,Long projection){store.jdbc().update("UPDATE compute_queue SET reason_code=?,updated_at=? WHERE request_id=? AND status='WAITING'",reason,utc(ComputeEvidenceService.now()),request);return new Outcome("WAITING",reason,null,null,null,projection,queue.detail(request),false);}
    private ComputeQueueService.Entry optionalQueue(long id){return store.jdbc().queryForObject("SELECT COUNT(*) FROM compute_queue WHERE request_id=?",Integer.class,id)==0?null:queue.detail(id);}
    private Receipt get(long id){return store.jdbc().query(SELECT+" WHERE r.id=?",r->{if(!r.next())throw new BizException("预留回执不存在");return receipt(r);},id);}
    Detail detail(long id){var receipt=get(id);var j=store.jdbc();var decisions=j.queryForList("SELECT id,kind,outcome,reason_code,previous_decision_id,supersedes_decision_id FROM decision WHERE id IN (?,?) ORDER BY id",receipt.admissionDecisionId(),receipt.submissionDecisionId());var checks=j.queryForList("SELECT c.id,c.decision_id,v.rule_code,v.version,c.rule_version_id,c.definition_version,c.result,c.reason_code,c.facts,c.output FROM rule_check c JOIN rule_version v ON v.id=c.rule_version_id WHERE c.decision_id IN (?,?) ORDER BY c.id",receipt.admissionDecisionId(),receipt.submissionDecisionId());for(var c:checks){c.put("facts",read(c.get("facts").toString()));c.put("output",read(c.get("output").toString()));}return new Detail(receipt,decisions,checks);}
    PageResult<Receipt> list(int page,int size,Long request){var args=new ArrayList<Object>();String where=request==null?"":" WHERE r.request_id=?";if(request!=null){if(request<=0)throw new BizException("requestId 必须是正整数");args.add(request);}long total=store.jdbc().queryForObject("SELECT COUNT(*) FROM compute_reservation_receipt r"+where,Long.class,args.toArray());args.add(size);args.add((page-1L)*size);return PageResult.of(store.jdbc().query(SELECT+where+" ORDER BY r.id DESC LIMIT ? OFFSET ?",(r,i)->receipt(r),args.toArray()),total,page,size);}
    private Receipt receipt(ResultSet r)throws SQLException{return new Receipt(r.getLong("id"),r.getLong("request_id"),r.getLong("job_id"),r.getLong("allocation_id"),r.getLong("group_id"),r.getLong("node_id"),r.getString("allocation_status"),r.getLong("rule_set_id"),r.getLong("snapshot_id"),r.getLong("projection_run_id"),r.getLong("admission_decision_id"),r.getLong("submission_decision_id"),r.getObject("restore_revision_id",Long.class),r.getObject("as_of",LocalDateTime.class).toInstant(ZoneOffset.UTC),r.getString("actor"));}
    private JsonNode read(String value){try{return json.readTree(value);}catch(Exception e){throw new BizException("预留回执 JSON 无法解析");}}
    private static LocalDateTime utc(Instant t){return LocalDateTime.ofInstant(t,ZoneOffset.UTC);}
}
