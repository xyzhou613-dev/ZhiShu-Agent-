package com.bemodel.compute;

import com.bemodel.common.BizException;
import com.bemodel.common.PageResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service @Profile("compute")
final class ComputeQueueService {
    record Entry(long requestId,Long nodeId,Long targetGroupId,String targetGroupCode,int priority,Instant submittedAt,
                 String status,String reasonCode,Long lastSelectionId,Instant lastEvaluatedAt,Instant nextEvaluateAt,
                 Long position,boolean head,boolean authorizesExecution) {}
    private static final String SELECT="""
            SELECT q.*,g.group_code,CASE WHEN q.status='WAITING' AND q.target_group_id IS NOT NULL THEN 1+(
              SELECT COUNT(*) FROM compute_queue x WHERE x.node_id=q.node_id AND x.target_group_id=q.target_group_id AND x.status='WAITING'
              AND (x.priority>q.priority OR x.priority=q.priority AND (x.submitted_at<q.submitted_at OR x.submitted_at=q.submitted_at AND x.request_id<q.request_id))
            ) ELSE NULL END AS queue_position FROM compute_queue q LEFT JOIN gpu_group g ON g.id=q.target_group_id
            """;
    private final ComputeDatabase.Store store;
    private final ComputeTargetSelectionService targets;
    private final ObjectMapper json;
    ComputeQueueService(ComputeDatabase.Store store,ComputeTargetSelectionService targets,ObjectMapper json){this.store=store;this.targets=targets;this.json=json;}
    static void validate(JsonNode body){
        ComputeEvidenceService.validateRequest(body);
        for(String key:List.of("priority","submittedAt","targetGroupId","gpuCount","jobId"))if(body.has(key))throw new BizException("队列等级、顺序和目标由服务端确定");
    }
    Entry enqueue(JsonNode body){
        validate(body);var tx=store.transactions();tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx.execute(status->{
            long nodeId=ComputeSchedulingLocks.node(store.jdbc(),body.path("nodeKey").asText());
            var j=store.jdbc();long id=body.path("requestId").asLong();
            var request=j.queryForList("SELECT status FROM compute_request WHERE id=? FOR UPDATE",id);
            if(request.isEmpty())throw new BizException("算力申请不存在");
            if(!List.of("SUBMITTED","APPROVED").contains(request.getFirst().get("status")))throw new BizException("仅已提交或已批准的待调度申请可入队");
            if(j.queryForObject("SELECT COUNT(*) FROM gpu_allocation a JOIN compute_job b ON b.id=a.job_id WHERE b.request_id=? AND a.status IN ('PENDING','OCCUPIED')",Integer.class,id)>0)
                throw new BizException("申请已有有效分配，不重复入队");
            if(!pendingJobPossible(id))throw new BizException("当前作业不可重新分配，请先完成失败作业重试");
            var old=j.queryForList("SELECT node_id FROM compute_queue WHERE request_id=? FOR UPDATE",id);
            if(!old.isEmpty()&&old.getFirst().get("node_id")!=null&&((Number)old.getFirst().get("node_id")).longValue()!=nodeId)throw new BizException("申请已绑定其他节点队列");
            refresh(body,nodeId);return detail(id);
        });
    }

    List<Entry> reevaluate(String nodeKey){
        var tx=store.transactions();tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx.execute(status->{long node=ComputeSchedulingLocks.node(store.jdbc(),nodeKey);return refreshLocked(nodeKey,node);});
    }
    /** Called under the same node/group lock used by the reservation transaction. */
    List<Entry> refreshLocked(String nodeKey,long nodeId){
        var j=store.jdbc();
        var ids=j.queryForList("SELECT request_id FROM compute_queue WHERE node_id=? AND status='WAITING' ORDER BY request_id",Long.class,nodeId);
        for(long id:ids)j.queryForList("SELECT id FROM compute_request WHERE id=? FOR UPDATE",Long.class,id);
        for(long id:ids){
            String state=j.queryForObject("SELECT status FROM compute_request WHERE id=? FOR UPDATE",String.class,id);
            if(!List.of("SUBMITTED","APPROVED").contains(state)){
                j.update("UPDATE compute_queue SET status='CANCELLED',reason_code='REQUEST_NOT_ACTIVE',next_evaluate_at=NULL WHERE request_id=?",id);continue;
            }
            if(j.queryForObject("SELECT COUNT(*) FROM gpu_allocation a JOIN compute_job b ON b.id=a.job_id WHERE b.request_id=? AND a.status IN ('PENDING','OCCUPIED')",Integer.class,id)>0){
                j.update("UPDATE compute_queue SET status='RESERVED',reason_code='ALREADY_RESERVED',next_evaluate_at=NULL WHERE request_id=?",id);continue;
            }
            if(!pendingJobPossible(id)){j.update("UPDATE compute_queue SET status='CANCELLED',reason_code='NEEDS_PENDING_JOB',next_evaluate_at=NULL WHERE request_id=?",id);continue;}
            refresh(json.createObjectNode().put("nodeKey",nodeKey).put("requestId",id),nodeId);
        }
        return ids.stream().map(this::detail).toList();
    }
    private boolean pendingJobPossible(long request){
        var j=store.jdbc();var jobs=j.queryForList("SELECT b.id,b.status FROM active_job a JOIN compute_job b ON b.id=a.job_id WHERE a.request_id=? FOR UPDATE",request);
        if(jobs.isEmpty())return j.queryForObject("SELECT COUNT(*) FROM compute_job WHERE request_id=?",Integer.class,request)==0;
        var job=jobs.getFirst();return "PENDING".equals(job.get("status"))&&j.queryForObject("SELECT COUNT(*) FROM gpu_allocation WHERE job_id=?",Integer.class,job.get("id"))==0;
    }
    private void refresh(JsonNode body,long nodeId){
        var selected=targets.select(body);var s=selected.selection();var j=store.jdbc();
        var settings=read(j.queryForObject("SELECT settings FROM rule_set WHERE id=?",String.class,s.ruleSetId()));
        var ledger=selected.evidenceSnapshot().evidence().getFirst().values();
        var request=ledger.path("request");
        int priority="TRAINING".equals(request.path("type_code").asText())?settings.path("trainingPriority").asInt():request.path("priority").asInt();
        String reason=s.targetGroupId()==null?"QUEUE_NO_TARGET":selected.output().path("blockingReasons").isEmpty()?"AWAITING_ARBITRATION":selected.output().path("blockingReasons").get(0).asText();
        j.update("""
                INSERT INTO compute_queue(request_id,node_id,target_group_id,priority,submitted_at,status,reason_code,last_selection_id,last_evaluated_at,next_evaluate_at,updated_at)
                SELECT id,?,?,?,submitted_at,'WAITING',?,?,?,?,? FROM compute_request WHERE id=?
                ON DUPLICATE KEY UPDATE target_group_id=VALUES(target_group_id),node_id=VALUES(node_id),priority=VALUES(priority),
                  status='WAITING',reason_code=VALUES(reason_code),last_selection_id=VALUES(last_selection_id),
                  last_evaluated_at=VALUES(last_evaluated_at),next_evaluate_at=VALUES(next_evaluate_at),updated_at=VALUES(updated_at)
                """,nodeId,s.targetGroupId(),priority,reason,s.id(),utc(s.asOf()),utc(s.asOf().plusSeconds(30)),utc(s.asOf()),s.requestId());
    }
    PageResult<Entry> list(String nodeKey,String status,int pageNum,int pageSize){
        if(!List.of("WAITING","RESERVED","CANCELLED").contains(status))throw new BizException("未知队列状态");
        var j=store.jdbc();long total=j.queryForObject("SELECT COUNT(*) FROM compute_queue q JOIN compute_node n ON n.id=q.node_id WHERE n.node_key=? AND q.status=?",Long.class,nodeKey,status);
        var entries=j.query(SELECT+" WHERE q.node_id=(SELECT id FROM compute_node WHERE node_key=?) AND q.status=? ORDER BY q.priority DESC,q.submitted_at,q.request_id LIMIT ? OFFSET ?",(r,i)->entry(r),nodeKey,status,pageSize,(pageNum-1L)*pageSize);
        return PageResult.of(entries,total,pageNum,pageSize);
    }
    Entry detail(long id){return store.jdbc().query(SELECT+" WHERE q.request_id=?",r->{if(!r.next())throw new BizException("队列记录不存在");return entry(r);},id);}
    private Entry entry(ResultSet r)throws SQLException{
        Long position=r.getObject("queue_position",Long.class);
        return new Entry(r.getLong("request_id"),r.getObject("node_id",Long.class),r.getObject("target_group_id",Long.class),r.getString("group_code"),r.getInt("priority"),instant(r,"submitted_at"),r.getString("status"),r.getString("reason_code"),r.getObject("last_selection_id",Long.class),instant(r,"last_evaluated_at"),instant(r,"next_evaluate_at"),position,position!=null&&position==1,false);
    }
    private JsonNode read(String s){try{return json.readTree(s);}catch(Exception e){throw new BizException("队列规则配置无法解析");}}
    private static Instant instant(ResultSet r,String key)throws SQLException{var d=r.getObject(key,LocalDateTime.class);return d==null?null:d.toInstant(ZoneOffset.UTC);}
    private static LocalDateTime utc(Instant t){return LocalDateTime.ofInstant(t,ZoneOffset.UTC);}
}
