package com.bemodel.compute;

import com.bemodel.common.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** One durable step per claim. Node calls always happen after the claim transaction commits. */
@Service @Profile("compute")
final class ComputeWorkflowService {
    @org.springframework.beans.factory.annotation.Autowired(required=false) ComputePreemptionWorkflow preemption;
    private final ComputeDatabase.Store store;private final ComputeActionService actions;
    private final ComputeCapacityProjectionService projections;private final ObjectMapper json;
    ComputeWorkflowService(ComputeDatabase.Store store,ComputeActionService actions,ComputeCapacityProjectionService projections,ObjectMapper json){this.store=store;this.actions=actions;this.projections=projections;this.json=json;}
    // A pre-lock identity lookup must not pin an old InnoDB read view. Resource reads follow
    // node/group/request locks; evidence is then sealed from that protected ledger.
    org.springframework.transaction.support.TransactionTemplate resourceTransactions(){var tx=store.transactions();tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);return tx;}
    Map<String,Object> create(long receiptId){return resourceTransactions().execute(tx->{
        var j=store.jdbc();var receipt=j.queryForMap("SELECT * FROM compute_reservation_receipt WHERE id=?",receiptId);
        ComputeSchedulingLocks.node(j,n(receipt,"node_id"));j.queryForMap("SELECT id FROM compute_request WHERE id=? FOR UPDATE",receipt.get("request_id"));
        var old=j.queryForList("SELECT id FROM workflow_run WHERE managed=TRUE AND job_id=?",receipt.get("job_id"));if(!old.isEmpty())return detail(n(old.getFirst(),"id"));
        var a=j.queryForMap("SELECT a.*,b.effective_gpu_count,g.group_code,g.current_hold_id FROM gpu_allocation a JOIN compute_job b ON b.id=a.job_id JOIN gpu_group g ON g.id=a.group_id WHERE a.id=? FOR UPDATE",receipt.get("allocation_id"));
        if(!"PENDING".equals(a.get("status")))throw new BizException("分配不再待生效");
        boolean flex=n(a,"effective_gpu_count")==1&&"FLEX".equals(a.get("group_code"))&&a.get("current_hold_id")==null&&receipt.get("restore_revision_id")==null;
        Map<String,Object> pinned=null;if(!flex){if(preemption==null)throw new BizException("抢占模块不可用");pinned=preemption.pin(receipt,a);}
        long id=ComputeReleaseBridge.insert(j,"INSERT INTO workflow_run(request_id,decision_id,group_id,job_id,step,managed,next_run_at) VALUES(?,?,?,?,?,TRUE,?)",receipt.get("request_id"),receipt.get("submission_decision_id"),receipt.get("group_id"),receipt.get("job_id"),flex?"HANDOVER":"NOTIFY",utc());
        if(pinned!=null)j.update("UPDATE workflow_run SET original_deployment_id=?,original_hold_id=?,restore_revision_id=?,restore_spec_id=?,notification_recipients=?,recovery_state='OWED' WHERE id=?",pinned.get("deployment"),pinned.get("hold"),receipt.get("restore_revision_id"),pinned.get("spec"),pinned.get("recipients"),id);return detail(id);
    });}
    Map<String,Object> detail(long id){var rows=store.jdbc().queryForList("SELECT w.*,g.group_code FROM workflow_run w JOIN gpu_group g ON g.id=w.group_id WHERE w.id=? AND w.managed=TRUE",id);if(rows.isEmpty())throw new BizException("执行流程不存在");var result=rows.getFirst();result.remove("lease_token");result.remove("notification_recipients");result.put("actions",store.jdbc().queryForList("SELECT id,action_type,workflow_step,status,attempt_count,error_code FROM execution_action WHERE workflow_run_id=? ORDER BY id",id));return result;}
    PageResult<Map<String,Object>> list(int page,int size){var j=store.jdbc();long total=j.queryForObject("SELECT COUNT(*) FROM workflow_run WHERE managed=TRUE",Long.class);return PageResult.of(j.queryForList("SELECT w.*,g.group_code FROM workflow_run w JOIN gpu_group g ON g.id=w.group_id WHERE managed=TRUE ORDER BY w.id DESC LIMIT ? OFFSET ?",size,(page-1L)*size).stream().map(row->{row.remove("lease_token");row.remove("notification_recipients");return row;}).toList(),total,page,size);}
    Map<String,Object> stop(long id,boolean cancel){store.transactions().executeWithoutResult(tx->{var w=locked(id);if(terminal(w))return;String desired=cancel?"CANCELLED":"STOPPED";if("CANCELLED".equals(w.get("desired_state")))desired="CANCELLED";store.jdbc().update("UPDATE workflow_run SET desired_state=?,next_run_at=?,updated_at=? WHERE id=?",desired,utc(),utc(),id);});return detail(id);}
    Map<String,Object> advance(long id){String token=UUID.randomUUID().toString();boolean acquired=Boolean.TRUE.equals(store.transactions().execute(tx->{var w=locked(id);if(terminal(w))return false;
        if(w.get("lease_until")!=null&&time(w.get("lease_until")).isAfter(Instant.now()))return false;
        store.jdbc().update("UPDATE workflow_run SET lease_token=?,lease_until=?,status='RUNNING' WHERE id=?",token,LocalDateTime.ofInstant(Instant.now().plusSeconds(90),ZoneOffset.UTC),id);return true;}));
        if(!acquired)return detail(id);
        try{store.jdbc().update("UPDATE workflow_run SET last_error=NULL WHERE id=? AND lease_token=?",id,token);var w=store.jdbc().queryForMap("SELECT * FROM workflow_run WHERE id=?",id);String step=w.get("step").toString();
            if(w.get("original_deployment_id")!=null)preemption.advance(this,w,token);
            else if("HANDOVER".equals(step))handover(id,token);
            else if("RUNNING".equals(step)){if(!"RUNNING".equals(w.get("desired_state")))transition(id,token,"STOP_JOB",null);}
            else if("START_JOB".equals(step)||"STOP_JOB".equals(step))act(w,token);
            else if("RELEASE".equals(step))release(id,token);
            else throw new BizException("未知流程步骤");
        }catch(RuntimeException e){store.jdbc().update("UPDATE workflow_run SET last_error=?,recovery_state=CASE WHEN step='RESTORE_CHECK' THEN 'WAITING' ELSE recovery_state END WHERE id=? AND lease_token=?",e instanceof BizException?e.getMessage():"流程推进失败，请核对服务端日志或重试",id,token);org.slf4j.LoggerFactory.getLogger(getClass()).debug("Workflow step failed: {}",id,e);}
        finally{store.jdbc().update("UPDATE workflow_run SET lease_token=NULL,lease_until=NULL,next_run_at=CASE WHEN status IN ('COMPLETED','CANCELLED') THEN NULL ELSE ? END WHERE id=? AND lease_token=?",LocalDateTime.ofInstant(Instant.now().plusSeconds(5),ZoneOffset.UTC),id,token);}
        return detail(id);
    }
    void act(Map<String,Object> w,String token){long id=n(w,"id");String step=w.get("step").toString();String key="workflow:"+id+":"+step+":"+w.get("row_version");
        // Resolve the original attempt before honoring cancellation; an unknown start must never be skipped.
        var existing=store.jdbc().queryForList("SELECT id FROM execution_action WHERE idempotency_key=?",key);
        if("START_JOB".equals(step)&&existing.isEmpty()&&!"RUNNING".equals(w.get("desired_state"))){transition(id,token,"STOP_JOB",null);return;}
        var action=existing.isEmpty()?actions.prepare(new ComputeActionService.Intent(id,step,"JOB",n(w,"job_id"),key,json.createObjectNode())):actions.get(n(existing.getFirst(),"id"));
        if("PENDING".equals(action.status()))action=actions.dispatch(action.id());
        else if(List.of("UNKNOWN","RUNNING").contains(action.status()))action=actions.reconcile(action.id());
        if("SUCCEEDED".equals(action.status())){
            if("START_JOB".equals(step)){
                if(!action.receipt().path("running").asBoolean())throw new BizException("启动回执未确认运行");
                store.transactions().executeWithoutResult(tx->{var current=owned(id,token);store.jdbc().update("UPDATE compute_job SET status='RUNNING',started_at=COALESCE(started_at,?) WHERE id=?",utc(),current.get("job_id"));move(id,"RUNNING",null);});
            }else {if(action.receipt().path("running").asBoolean(true)||!action.receipt().path("gpuReleased").asBoolean())throw new BizException("停止回执尚未确认退出和 GPU 释放");transition(id,token,"RELEASE",null);}
        }else if("FAILED".equals(action.status())&&"START_JOB".equals(step))transition(id,token,"STOP_JOB","START_FAILED");
        else if("FAILED".equals(action.status()))throw new BizException("STOP_JOB_FAILED：尚未确认退出，保留资源；请核对动作原因并由管理员安全重试");
        // A failed stop is never retried automatically: ADMIN may use the N-12 safe retry endpoint.
    }
    private void handover(long id,String token){resourceTransactions().executeWithoutResult(tx->{var w=resourceLocked(id,token);var j=store.jdbc();var a=allocation(w);
        if(!"RUNNING".equals(w.get("desired_state"))){closePending(w,a);return;}
        if(!"PENDING".equals(a.get("status")))throw new BizException("预留已不再待生效");
        var group=j.queryForMap("SELECT * FROM gpu_group WHERE id=? FOR UPDATE",w.get("group_id"));
        if(group.get("current_hold_id")!=null||j.queryForObject("SELECT COUNT(*) FROM hold_period WHERE group_id=? AND ended_at IS NULL",Integer.class,w.get("group_id"))>0||!Objects.equals(group.get("pending_allocation_id"),a.get("id")))throw new BizException("目标组不再空闲或预留指针不一致");
        var receipt=j.queryForMap("SELECT r.*,n.node_key FROM compute_reservation_receipt r JOIN compute_node n ON n.id=r.node_id WHERE r.job_id=?",w.get("job_id"));
        var projection=projections.project(json.createObjectNode().put("nodeKey",receipt.get("node_key").toString()).put("requestId",n(w,"request_id")).put("mode","PLAN"));
        if(projection.run().ruleSetId()!=n(receipt,"rule_set_id")||!"FEASIBLE".equals(projection.run().feasibility()))throw new BizException("规则集合变化或最新容量/证据检查未通过");
        for(var e:projection.evidenceSnapshot().evidence())if(e.observationId()!=null){if(!ComputeResourceMath.evidenceValid(e.observedAt(),e.validUntil(),Instant.now(),e.values().path("maxAgeSeconds").asLong())||!e.values().path("usable").asBoolean())throw new BizException("交接证据已过期");}
        var cards=j.queryForList("SELECT gpu_id FROM allocation_gpu WHERE allocation_id=?",Long.class,a.get("id"));if(cards.size()!=1)throw new BizException("单卡成员不完整");
        for(var e:projection.evidenceSnapshot().evidence())if(e.observationId()!=null&&"GPU".equals(e.values().path("subjectKind").asText())&&cards.contains(e.values().path("subjectId").asLong())&&e.values().path("occupied").asBoolean())throw new BizException("目标 GPU 观测仍被占用");
        j.update("UPDATE gpu_allocation SET status='OCCUPIED',activated_at=? WHERE id=?",utc(),a.get("id"));long hold=ComputeReleaseBridge.insert(j,"INSERT INTO hold_period(group_id,allocation_id,started_at) VALUES(?,?,?)",w.get("group_id"),a.get("id"),utc());
        j.update("UPDATE gpu_group SET current_hold_id=?,pending_allocation_id=NULL WHERE id=?",hold,w.get("group_id"));event(w,"WORKFLOW_HANDOVER");move(id,"START_JOB",null);
        for(var e:projection.evidenceSnapshot().evidence())if(e.observationId()!=null&&!ComputeResourceMath.evidenceValid(e.observedAt(),e.validUntil(),Instant.now(),e.values().path("maxAgeSeconds").asLong()))throw new BizException("交接提交前证据已过期，事务已回滚");
    });}
    private void release(long id,String token){resourceTransactions().executeWithoutResult(tx->{var w=resourceLocked(id,token);var j=store.jdbc();var a=allocation(w);
        var stops=j.queryForList("SELECT receipt FROM execution_action WHERE workflow_run_id=? AND action_type='STOP_JOB' AND status='SUCCEEDED' ORDER BY id DESC LIMIT 1",id);
        if(stops.isEmpty())throw new BizException("缺少停止确认");JsonNode receipt;try{receipt=json.readTree(stops.getFirst().get("receipt").toString());}catch(Exception e){throw new BizException("停止回执无效");}
        if(receipt.path("running").asBoolean(true)||!receipt.path("gpuReleased").asBoolean())throw new BizException("尚未确认退出");
        var hold=j.queryForMap("SELECT h.* FROM hold_period h JOIN gpu_group g ON g.current_hold_id=h.id WHERE h.allocation_id=? AND h.ended_at IS NULL FOR UPDATE",a.get("id"));
        var at=utc();j.update("UPDATE hold_period SET ended_at=? WHERE id=?",at,hold.get("id"));j.update("UPDATE gpu_group SET current_hold_id=NULL WHERE id=? AND current_hold_id=?",w.get("group_id"),hold.get("id"));j.update("UPDATE gpu_allocation SET status='RELEASED',closed_at=?,close_reason=? WHERE id=?",at,reason(w),a.get("id"));finish(w);event(w,"WORKFLOW_RELEASED");
    });}
    private void closePending(Map<String,Object>w,Map<String,Object>a){var j=store.jdbc();if(!"PENDING".equals(a.get("status")))throw new BizException("取消时预留已变化");j.update("UPDATE gpu_group SET pending_allocation_id=NULL WHERE id=? AND pending_allocation_id=?",w.get("group_id"),a.get("id"));j.update("UPDATE gpu_allocation SET status='CANCELLED',closed_at=?,close_reason='USER_CANCELLED' WHERE id=?",utc(),a.get("id"));finish(w);event(w,"WORKFLOW_CANCELLED");}
    private void finish(Map<String,Object>w){String reason=reason(w);String job="START_FAILED".equals(reason)?"FAILED":"CANCELLED".equals(reason)?"CANCELLED":"STOPPED";var j=store.jdbc();j.update("UPDATE compute_job SET status=?,ended_at=? WHERE id=?",job,utc(),w.get("job_id"));j.update("DELETE FROM active_job WHERE request_id=? AND job_id=?",w.get("request_id"),w.get("job_id"));if(!"FAILED".equals(job))j.update("UPDATE compute_request SET status=? WHERE id=?","CANCELLED".equals(job)?"CANCELLED":"COMPLETED",w.get("request_id"));j.update("UPDATE compute_queue SET status='CANCELLED',reason_code=?,next_evaluate_at=NULL WHERE request_id=?",reason,w.get("request_id"));j.update("UPDATE workflow_run SET step='DONE',status=?,end_reason=?,row_version=row_version+1,updated_at=? WHERE id=?","CANCELLED".equals(reason)?"CANCELLED":"COMPLETED",reason,utc(),w.get("id"));}
    private String reason(Map<String,Object>w){return w.get("end_reason")!=null?w.get("end_reason").toString():"CANCELLED".equals(w.get("desired_state"))?"CANCELLED":"STOPPED";}
    Map<String,Object> allocation(Map<String,Object>w){return store.jdbc().queryForMap("SELECT * FROM gpu_allocation WHERE job_id=? FOR UPDATE",w.get("job_id"));}
    Map<String,Object> resourceLocked(long id,String token){var hint=store.jdbc().queryForMap("SELECT w.*,g.node_id FROM workflow_run w JOIN gpu_group g ON g.id=w.group_id WHERE w.id=?",id);ComputeSchedulingLocks.node(store.jdbc(),n(hint,"node_id"));var ids=new TreeSet<>(store.jdbc().queryForList("SELECT request_id FROM compute_queue WHERE node_id=? AND status='WAITING'",Long.class,hint.get("node_id")));ids.add(n(hint,"request_id"));for(long request:ids)store.jdbc().queryForMap("SELECT id FROM compute_request WHERE id=? FOR UPDATE",request);store.jdbc().queryForMap("SELECT id FROM compute_job WHERE id=? FOR UPDATE",hint.get("job_id"));return owned(id,token);}
    private Map<String,Object> locked(long id){var rows=store.jdbc().queryForList("SELECT * FROM workflow_run WHERE id=? AND managed=TRUE FOR UPDATE",id);if(rows.isEmpty())throw new BizException("执行流程不存在");return rows.getFirst();}
    Map<String,Object> owned(long id,String token){var w=locked(id);if(!token.equals(w.get("lease_token"))||!time(w.get("lease_until")).isAfter(Instant.now()))throw new BizException("流程领取已失效");return w;}
    private boolean terminal(Map<String,Object>w){return List.of("COMPLETED","CANCELLED").contains(w.get("status"));}
    void transition(long id,String token,String step,String reason){store.transactions().executeWithoutResult(tx->{owned(id,token);move(id,step,reason);});}
    void move(long id,String step,String reason){store.jdbc().update("UPDATE workflow_run SET step=?,row_version=row_version+1,end_reason=COALESCE(?,end_reason),updated_at=? WHERE id=?",step,reason,utc(),id);}
    void event(Map<String,Object>w,String type){store.jdbc().update("INSERT INTO resource_event(source,event_type,subject_kind,subject_id,causing_decision_id,occurred_at,impact_scope,payload) VALUES('COMPUTE_WORKFLOW',?,'JOB',?,?,?,'{}',?)",type,w.get("job_id"),w.get("decision_id"),utc(),json.createObjectNode().put("workflowId",n(w,"id")).toString());}
    private static long n(Map<String,Object>w,String key){return ((Number)w.get(key)).longValue();}
    private static LocalDateTime utc(){return LocalDateTime.ofInstant(Instant.now(),ZoneOffset.UTC);}
    private static Instant time(Object value){return ((LocalDateTime)value).toInstant(ZoneOffset.UTC);}
}
