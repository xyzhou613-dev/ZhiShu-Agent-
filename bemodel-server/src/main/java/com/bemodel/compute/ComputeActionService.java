package com.bemodel.compute;

import com.bemodel.auth.CurrentUser;
import com.bemodel.common.BizException;
import com.bemodel.common.PageResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** Persist intent, commit a claim, call outside all transactions, then atomically save receipt + event. */
@Service
@Profile("compute")
public class ComputeActionService {
    public record Intent(long workflowId, String actionType, String targetKind, long targetId,
                         String operationKey, JsonNode input) {}
    public record Action(long id, long workflowId, long decisionId, String actionType, String targetKind,
                         long targetId, String operationKey, String status, int attemptCount, int reconcileCount,
                         String executorKey, boolean retryAllowed, Instant leaseUntil, Instant startedAt,
                         Instant finishedAt, JsonNode input, JsonNode receipt, String errorCode,
                         String createdBy, Instant createdAt, Instant updatedAt, String workflowStep, Long workflowVersion) {}
    record Detail(Action action, List<Map<String,Object>> events) {}
    private record Claim(Action action, String token, ComputeActionExecutor executor, boolean query) {}
    private static final Set<String> TYPES = Set.of("NOTIFY", "DRAIN", "STOP", "HANDOVER", "START_JOB",
            "STOP_JOB", "FINALIZE", "RESTORE", "HEALTH_CHECK", "RESUME_TRAFFIC");
    private static final Set<String> STATES = Set.of("PENDING", "RUNNING", "UNKNOWN", "SUCCEEDED", "FAILED", "COMPENSATED");
    private final ComputeDatabase.Store store;
    private final ObjectMapper json;
    private final List<ComputeActionExecutor> executors;
    private final Duration timeout;

    ComputeActionService(ComputeDatabase.Store store, ObjectMapper json, List<ComputeActionExecutor> executors,
                         @Value("${compute.actions.timeout-seconds:30}") long timeoutSeconds) {
        if (timeoutSeconds < 1 || timeoutSeconds > 300) throw new IllegalArgumentException("动作超时必须为 1～300 秒");
        var keys = new HashSet<String>();
        for (var executor : executors) if (executor.key() == null || !executor.key().matches("[A-Za-z0-9_.:-]{1,64}") || !keys.add(executor.key()))
            throw new IllegalArgumentException("动作执行器编号必须有效且唯一");
        this.store=store; this.json=json; this.executors=List.copyOf(executors); this.timeout=Duration.ofSeconds(timeoutSeconds);
    }

    /** Internal workflow integration only. Caller must already have checked the current rule/step gates. */
    public Action prepare(Intent intent) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new BizException("动作登记须独立提交，请在流程事务结束后调用");
        validate(intent);
        try { return store.transactions().execute(tx -> {
            var j=store.jdbc();
            var flows=j.queryForList("SELECT * FROM workflow_run WHERE id=? FOR UPDATE", intent.workflowId());
            if (flows.isEmpty()) throw new BizException("执行流程不存在");
            var flow=flows.getFirst();
            var old=j.query("SELECT * FROM execution_action WHERE idempotency_key=? FOR UPDATE", (r,i)->action(r), intent.operationKey());
            long decision=((Number)flow.get("decision_id")).longValue();
            if (!old.isEmpty()) return same(old.getFirst(),intent,decision);
            if (!List.of("READY","RUNNING").contains(flow.get("status"))) throw new BizException("流程当前不可创建执行动作");
            if (j.queryForObject("SELECT COUNT(*) FROM decision WHERE id=? AND request_id=? AND outcome='APPROVED'", Integer.class, decision,flow.get("request_id"))!=1)
                throw new BizException("动作需要本流程的已批准决策");
            validateTarget(intent,flow);
            long id=ComputeReleaseBridge.insert(j,"""
                    INSERT INTO execution_action(workflow_run_id,decision_id,action_type,target_kind,target_id,idempotency_key,input_json,created_by,workflow_step,workflow_version)
                    VALUES(?,?,?,?,?,?,?,?,?,?)
                    """, intent.workflowId(),decision,intent.actionType(),intent.targetKind(),intent.targetId(),intent.operationKey(),intent.input().toString(),actor(),flow.get("step"),flow.get("row_version"));
            var a=get(id); event(a,"ACTION_PREPARED",json.createObjectNode().put("status","PENDING")); return a;
        }); } catch (DuplicateKeyException e) {
            // The failed transaction has ended before the winner is read.
            var existing=store.jdbc().query("SELECT * FROM execution_action WHERE idempotency_key=?", (r,i)->action(r),intent.operationKey());
            if (existing.isEmpty()) throw e;
            long decision=store.jdbc().queryForObject("SELECT decision_id FROM workflow_run WHERE id=?",Long.class,intent.workflowId());
            return same(existing.getFirst(),intent,decision);
        }
    }

    /** Initial send is deliberately unavailable as an HTTP endpoint. N-11 supplies trusted gates. */
    public Action dispatch(long id) { return call(id,false,false); }
    public Action reconcile(long id) { return call(id,true,false); }
    public Action retry(long id) { return call(id,false,true); }

    private Action call(long id, boolean query, boolean retry) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new BizException("执行器调用必须在业务事务提交后进行");
        Claim claim=store.transactions().execute(tx -> claim(id,query,retry));
        if (claim==null) return get(id);
        ComputeActionExecutor.Reply reply;
        try {
            var a=claim.action();
            var command=new ComputeActionExecutor.Command(a.id(),a.workflowId(),a.decisionId(),a.operationKey(),a.actionType(),a.targetKind(),a.targetId(),a.input().deepCopy());
            reply=query ? claim.executor().query(command,timeout) : claim.executor().execute(command,timeout);
            if (reply==null || reply.state()==null || reply.receipt()==null || !reply.receipt().isObject()
                    || reply.receipt().toString().length()>65536 || reply.errorCode()!=null && !reply.errorCode().matches("[A-Za-z0-9_.:-]{1,64}"))
                reply=unknown("INVALID_EXECUTOR_RECEIPT");
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // An exception cannot prove that the remote operation failed. Do not persist secret-bearing messages.
            reply=unknown("EXECUTOR_RESULT_UNKNOWN");
        }
        var result=reply;
        return store.transactions().execute(tx -> finish(claim,result));
    }

    private Claim claim(long id,boolean query,boolean retry) {
        var j=store.jdbc(); var identity=get(id);
        // Match prepare's workflow -> action order; never acquire these in reverse.
        var flow=j.queryForMap("SELECT * FROM workflow_run WHERE id=? FOR UPDATE",identity.workflowId());
        var a=locked(id); Instant now=Instant.now();
        if (List.of("SUCCEEDED","COMPENSATED").contains(a.status())) return null;
        if ("RUNNING".equals(a.status()) && a.leaseUntil()!=null && a.leaseUntil().isAfter(now)) return null;
        if (query) {
            if (!List.of("RUNNING","UNKNOWN").contains(a.status())) throw new BizException("仅运行超时或结果未知的动作需要核对");
        } else if (retry) {
            if (!"FAILED".equals(a.status()) || !a.retryAllowed()) throw new BizException("仅执行器确认失败且允许安全重试的动作可重试；未知结果请先核对");
        } else if (!"PENDING".equals(a.status())) {
            throw new BizException("动作已经发送；请查询原结果或使用重试入口");
        }
        if (a.input()==null) throw new BizException("旧动作缺少受管输入，不能自动执行或核对");
        if (!query && !List.of("READY","RUNNING").contains(flow.get("status"))) throw new BizException("流程当前不可执行动作");
        if (!query && (!Objects.equals(a.workflowStep(),flow.get("step")) || a.workflowVersion()==null
                || a.workflowVersion()!=((Number)flow.get("row_version")).longValue()
                || a.decisionId()!=((Number)flow.get("decision_id")).longValue()))
            throw new BizException("流程步骤、版本或决策已变化，禁止重发旧动作");
        var candidates=executors.stream().filter(e->a.executorKey()==null ? e.supports(a.actionType()) : a.executorKey().equals(e.key())).toList();
        if (candidates.size()!=1) throw new BizException(a.executorKey()==null ? "未配置唯一匹配的动作执行器，拒绝发送" : "原动作执行器不可用，拒绝更换执行器重发");
        String token=UUID.randomUUID().toString();
        j.update("""
                UPDATE execution_action SET status='RUNNING',executor_key=?,claim_token=?,lease_until=?,retry_allowed=FALSE,
                    attempt_count=attempt_count+?,reconcile_count=reconcile_count+?,started_at=COALESCE(started_at,?),
                    finished_at=NULL,updated_at=? WHERE id=?
                """,candidates.getFirst().key(),token,utc(now.plus(timeout).plusSeconds(5)),query?0:1,query?1:0,utc(now),utc(now),id);
        var current=get(id);event(current,query?"ACTION_QUERY_STARTED":retry?"ACTION_RETRY_STARTED":"ACTION_SEND_STARTED",
                json.createObjectNode().put("claimToken",token).put("attemptCount",current.attemptCount()).put("actor",actor()));
        return new Claim(current,token,candidates.getFirst(),query);
    }

    private Action finish(Claim claim,ComputeActionExecutor.Reply reply) {
        var j=store.jdbc();var a=locked(claim.action().id());
        String token=j.queryForObject("SELECT claim_token FROM execution_action WHERE id=?",String.class,a.id());
        if (!claim.token().equals(token) || !"RUNNING".equals(a.status())) {
            event(a,"ACTION_STALE_REPLY",json.createObjectNode().put("claimToken",claim.token()).put("reportedStatus",reply.state().name()));
            return a;
        }
        String state=reply.state().name();boolean terminal=reply.state()!=ComputeActionExecutor.State.UNKNOWN;
        boolean retry=reply.state()==ComputeActionExecutor.State.FAILED && reply.retryAllowed();
        Instant now=Instant.now();
        j.update("""
                UPDATE execution_action SET status=?,receipt=?,error_code=?,retry_allowed=?,claim_token=NULL,lease_until=NULL,
                    finished_at=?,updated_at=? WHERE id=?
                """,state,reply.receipt().toString(),reply.errorCode(),retry,terminal?utc(now):null,utc(now),a.id());
        var payload=json.createObjectNode().put("status",state).put("attemptCount",a.attemptCount()).put("reconcileCount",a.reconcileCount())
                .put("retryAllowed",retry).put("claimToken",claim.token());
        payload.set("receipt",reply.receipt());if(reply.errorCode()!=null)payload.put("errorCode",reply.errorCode());
        event(a,claim.query()?"ACTION_QUERY_RESULT":"ACTION_EXECUTION_RESULT",payload);return get(a.id());
    }

    Action get(long id) {
        if(id<=0)throw new BizException("动作编号必须为正整数");
        return store.jdbc().query("SELECT * FROM execution_action WHERE id=?",r->{if(!r.next())throw new BizException("执行动作不存在");return action(r);},id);
    }
    Detail detail(long id) {
        var a=get(id);var events=store.jdbc().queryForList("SELECT id,event_type,occurred_at,payload FROM resource_event WHERE action_id=? ORDER BY id",id);
        events.forEach(e->e.put("payload",read(e.get("payload").toString())));return new Detail(a,events);
    }
    PageResult<Action> list(int page,int size,Long workflow,String key,String status) {
        if(workflow!=null&&workflow<=0 || key!=null&&(key.isBlank()||key.length()>128) || status!=null&&!STATES.contains(status))throw new BizException("动作查询参数无效");
        var args=new ArrayList<Object>();String where=" WHERE 1=1";
        if(workflow!=null){where+=" AND workflow_run_id=?";args.add(workflow);}
        if(key!=null){where+=" AND idempotency_key=?";args.add(key);}
        if(status!=null){where+=" AND status=?";args.add(status);}
        long total=store.jdbc().queryForObject("SELECT COUNT(*) FROM execution_action"+where,Long.class,args.toArray());
        args.add(size);args.add((page-1L)*size);
        return PageResult.of(store.jdbc().query("SELECT * FROM execution_action"+where+" ORDER BY id DESC LIMIT ? OFFSET ?",(r,i)->action(r),args.toArray()),total,page,size);
    }
    private Action locked(long id) { return store.jdbc().query("SELECT * FROM execution_action WHERE id=? FOR UPDATE",r->{if(!r.next())throw new BizException("执行动作不存在");return action(r);},id); }
    private Action same(Action a,Intent i,long decision) {
        if(!a.operationKey().equals(i.operationKey()) || a.workflowId()!=i.workflowId() || a.decisionId()!=decision || !a.actionType().equals(i.actionType()) || !a.targetKind().equals(i.targetKind()) || a.targetId()!=i.targetId() || !Objects.equals(a.input(),i.input()))
            throw new BizException("同一操作编号对应的动作内容不同");return a;
    }
    private void validate(Intent i) {
        if(i==null || i.workflowId()<=0 || i.targetId()<=0 || !TYPES.contains(Objects.toString(i.actionType(),""))
                || !Set.of("JOB","DEPLOYMENT","ALLOCATION").contains(Objects.toString(i.targetKind(),""))
                || i.operationKey()==null || !i.operationKey().matches("[A-Za-z0-9_.:/-]{1,128}") || i.input()==null || !i.input().isObject() || i.input().toString().length()>65536)
            throw new BizException("动作类型、目标、固定操作编号或输入无效");
    }
    private void validateTarget(Intent i,Map<String,Object> flow) {
        long group=((Number)flow.get("group_id")).longValue();
        boolean valid=switch(i.targetKind()) {
            case "JOB" -> i.targetId()==((Number)flow.get("job_id")).longValue();
            case "ALLOCATION" -> store.jdbc().queryForObject("SELECT COUNT(*) FROM gpu_allocation WHERE id=? AND group_id=? AND job_id=?",Integer.class,i.targetId(),group,flow.get("job_id"))==1;
            case "DEPLOYMENT" -> store.jdbc().queryForObject("SELECT COUNT(*) FROM deployment WHERE id=? AND group_id=?",Integer.class,i.targetId(),group)==1;
            default -> false;
        };
        boolean typeMatches=switch(i.actionType()) {
            case "START_JOB", "STOP_JOB", "FINALIZE" -> "JOB".equals(i.targetKind());
            case "HANDOVER" -> "ALLOCATION".equals(i.targetKind());
            default -> "DEPLOYMENT".equals(i.targetKind());
        };
        if(!valid || !typeMatches)throw new BizException("动作类型与目标不匹配，或目标不属于当前流程");
    }
    private void event(Action a,String type,JsonNode payload) {
        ComputeNormGuards.eventSource("COMPUTE_ACTION",a.decisionId(),a.decisionId());
        store.jdbc().update("""
                INSERT INTO resource_event(source,event_type,subject_kind,subject_id,causing_decision_id,action_id,occurred_at,impact_scope,payload)
                VALUES('COMPUTE_ACTION',?,?,?,?,?,?,?,?)
                """,type,a.targetKind(),a.targetId(),a.decisionId(),a.id(),utc(Instant.now()),json.createObjectNode().put("workflowId",a.workflowId()).put("groupBound",true).toString(),payload.toString());
    }
    private Action action(ResultSet r)throws SQLException {
        return new Action(r.getLong("id"),r.getLong("workflow_run_id"),r.getLong("decision_id"),r.getString("action_type"),r.getString("target_kind"),r.getLong("target_id"),r.getString("idempotency_key"),r.getString("status"),r.getInt("attempt_count"),r.getInt("reconcile_count"),r.getString("executor_key"),r.getBoolean("retry_allowed"),time(r,"lease_until"),time(r,"started_at"),time(r,"finished_at"),read(r.getString("input_json")),read(r.getString("receipt")),r.getString("error_code"),r.getString("created_by"),time(r,"created_at"),time(r,"updated_at"),r.getString("workflow_step"),r.getObject("workflow_version",Long.class));
    }
    private JsonNode read(String value){if(value==null)return null;try{return json.readTree(value);}catch(Exception e){throw new BizException("动作 JSON 无法解析");}}
    private ComputeActionExecutor.Reply unknown(String code){return new ComputeActionExecutor.Reply(ComputeActionExecutor.State.UNKNOWN,json.createObjectNode(),code,false);}
    private static String actor(){return Objects.requireNonNullElse(CurrentUser.username(),"system");}
    private static LocalDateTime utc(Instant time){return LocalDateTime.ofInstant(time,ZoneOffset.UTC);}
    private static Instant time(ResultSet r,String key)throws SQLException{var t=r.getObject(key,LocalDateTime.class);return t==null?null:t.toInstant(ZoneOffset.UTC);}
}
