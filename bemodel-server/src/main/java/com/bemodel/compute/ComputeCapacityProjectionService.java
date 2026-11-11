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
import java.util.ArrayList;
import java.util.List;

@Service @Profile("compute")
final class ComputeCapacityProjectionService {
    record Run(long id,long requestId,long nodeId,String mode,Long targetGroupId,String targetGroupCode,
               String overallResult,String feasibility,long ruleSetId,long snapshotId,Instant asOf,String formulaVersion,
               String actor,boolean authorizesExecution,boolean queuePersisted) {}
    record Check(long ruleVersionId,String ruleCode,int ruleVersion,int definitionVersion,String result,String reasonCode,JsonNode facts,JsonNode output) {}
    record Detail(Run run,JsonNode proposal,JsonNode output,List<Check> checks,List<Long> evidenceIds,ComputeEvidenceService.Detail evidenceSnapshot) {}
    private static final String SELECT="""
            SELECT r.*,s.as_of,v.formula_version FROM capacity_projection_run r
            JOIN evidence_snapshot s ON s.id=r.snapshot_id JOIN rule_set v ON v.id=r.rule_set_id
            """;
    private final ComputeDatabase.Store store;
    private final ComputeRuleRunner runner;
    private final ComputeEvidenceService evidence;
    private final ObjectMapper json;
    ComputeCapacityProjectionService(ComputeDatabase.Store store,ComputeRuleRunner runner,ComputeEvidenceService evidence,ObjectMapper json){this.store=store;this.runner=runner;this.evidence=evidence;this.json=json;}

    static void validate(JsonNode body) {
        ComputeEvidenceService.validateRequest(body);
        for(String field:List.of("jobId","gpuCount","targetGroupId","targetGroupCode","capacityBytes","usedGpuIds","ruleSetId","releasedAllocationId"))
            if(body.has(field))throw new BizException("投影规格、目标和账本由服务端读取，不能由调用方覆盖");
        String mode=body.path("mode").asText("PLAN");
        if(body.has("mode")&&!body.path("mode").isTextual() || !List.of("PLAN","RESTORE").contains(mode))throw new BizException("投影 mode 必须是 PLAN 或 RESTORE");
        for(String field:List.of("pausedDeploymentId","restoreRevisionId"))if(body.has(field)
                && (!body.path(field).isIntegralNumber()||!body.path(field).canConvertToLong()||body.path(field).asLong()<=0))throw new BizException(field+" 必须是正整数");
        if("RESTORE".equals(mode) && (!body.has("restoreRevisionId")||body.has("pausedDeploymentId")))throw new BizException("恢复投影需要 restoreRevisionId，不能同时指定拟暂停部署");
        if("PLAN".equals(mode)&&body.has("restoreRevisionId"))throw new BizException("方案投影不能指定恢复修订");
    }

    Detail project(JsonNode body) {
        validate(body);var tx=store.transactions();tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx.execute(status->{
            var rules=runner.pinActive();var captured=evidence.capture(body,rules,ComputeEvidenceService.now());
            JsonNode settings=read(store.jdbc().queryForObject("SELECT settings FROM rule_set WHERE id=?",String.class,rules.ruleSetId()));
            var projection=new ComputeCapacityProjector(json).project(captured,rules,settings,body);
            JdbcTemplate j=store.jdbc();
            long id=ComputeReleaseBridge.insert(j,"""
                    INSERT INTO capacity_projection_run(snapshot_id,rule_set_id,node_id,request_id,mode,target_group_id,
                        target_group_code,overall_result,feasibility,actor,proposal,output) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                    """,captured.snapshot().id(),rules.ruleSetId(),captured.snapshot().nodeId(),captured.snapshot().requestId(),projection.mode(),
                    projection.targetGroupId(),projection.targetGroupCode(),projection.overallResult().name(),projection.feasibility(),captured.snapshot().actor(),body.toString(),projection.output().toString());
            for(var c:projection.checks())j.update("""
                    INSERT INTO capacity_projection_check(run_id,snapshot_id,rule_set_id,rule_version_id,definition_version,result,reason_code,facts,output)
                    VALUES(?,?,?,?,?,?,?,?,?)
                    """,id,captured.snapshot().id(),rules.ruleSetId(),c.rule().ruleVersionId(),c.rule().definitionVersion(),c.result().name(),c.reasonCode(),c.facts().toString(),c.output().toString());
            for(var e:captured.evidence())j.update("INSERT INTO capacity_projection_evidence(run_id,snapshot_id,evidence_id) VALUES(?,?,?)",id,captured.snapshot().id(),e.id());
            return detail(id);
        });
    }
    PageResult<Run> list(int pageNum,int pageSize,Long requestId) {
        if(requestId!=null&&requestId<=0)throw new BizException("requestId 必须是正整数");
        var args=new ArrayList<Object>();if(requestId!=null)args.add(requestId);
        String where=requestId==null?"":" WHERE r.request_id=?";JdbcTemplate j=store.jdbc();
        long total=j.queryForObject("SELECT COUNT(*) FROM capacity_projection_run r"+where,Long.class,args.toArray());
        args.add(pageSize);args.add((pageNum-1L)*pageSize);
        return PageResult.of(j.query(SELECT+where+" ORDER BY r.id DESC LIMIT ? OFFSET ?",(r,i)->summary(r),args.toArray()),total,pageNum,pageSize);
    }
    Detail detail(long id) {
        var j=store.jdbc();return j.query(SELECT+" WHERE r.id=?",rows->{
            if(!rows.next())throw new BizException("容量投影记录不存在");
            var run=summary(rows);var proposal=read(rows.getString("proposal"));var output=read(rows.getString("output"));
            var checks=j.query("""
                    SELECT c.*,v.rule_code,v.version FROM capacity_projection_check c JOIN rule_version v ON v.id=c.rule_version_id
                    WHERE c.run_id=? ORDER BY v.rule_code
                    """,(r,i)->new Check(r.getLong("rule_version_id"),r.getString("rule_code"),r.getInt("version"),r.getInt("definition_version"),r.getString("result"),r.getString("reason_code"),read(r.getString("facts")),read(r.getString("output"))),id);
            var refs=j.queryForList("SELECT evidence_id FROM capacity_projection_evidence WHERE run_id=? ORDER BY evidence_id",Long.class,id);
            return new Detail(run,proposal,output,checks,refs,evidence.detail(run.snapshotId()));
        },id);
    }
    private Run summary(ResultSet r)throws SQLException{return new Run(r.getLong("id"),r.getLong("request_id"),r.getLong("node_id"),r.getString("mode"),r.getObject("target_group_id",Long.class),r.getString("target_group_code"),r.getString("overall_result"),r.getString("feasibility"),r.getLong("rule_set_id"),r.getLong("snapshot_id"),r.getObject("as_of",LocalDateTime.class).toInstant(ZoneOffset.UTC),r.getString("formula_version"),r.getString("actor"),false,false);}
    private JsonNode read(String value){try{return json.readTree(value);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new BizException("容量投影 JSON 无法解析");}}
}
