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
import static com.bemodel.compute.ComputeRuleEvaluator.Status;

@Service
@Profile("compute")
final class ComputeTargetSelectionService {
    record Selection(long id, long requestId, long nodeId, Long jobId, int gpuCount, String specBasis,
                     Long targetGroupId, String targetGroupCode, String selectionStatus, Status ruleResult,
                     String reasonCode, long ruleSetId, long ruleVersionId, int ruleVersion, int definitionVersion,
                     long snapshotId, Instant asOf, String actor, boolean queuePersisted, boolean authorizesExecution) {}
    record Detail(Selection selection, JsonNode facts, JsonNode output, List<Long> evidenceIds,
                  ComputeEvidenceService.Detail evidenceSnapshot) {}
    private static final String SELECT = """
            SELECT t.*,s.as_of,v.version AS rule_version FROM target_group_selection t
            JOIN evidence_snapshot s ON s.id=t.snapshot_id JOIN rule_version v ON v.id=t.rule_version_id
            """;
    private final ComputeDatabase.Store store;
    private final ComputeRuleRunner runner;
    private final ComputeEvidenceService evidence;
    private final ObjectMapper json;
    private final ComputeTargetSelector selector;

    ComputeTargetSelectionService(ComputeDatabase.Store store, ComputeRuleRunner runner, ComputeEvidenceService evidence, ObjectMapper json) {
        this.store=store; this.runner=runner; this.evidence=evidence; this.json=json;
        this.selector=new ComputeTargetSelector(json);
    }

    Detail select(JsonNode body) {
        ComputeEvidenceService.validateRequest(body);
        for (String key : List.of("jobId","gpuCount","targetGroupId","targetGroupCode","targetGroup","flex","ruleSetId"))
            if (body.has(key)) throw new BizException("目标、卡数和当前作业由服务端确定，不能由调用方指定");
        var tx=store.transactions(); tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx.execute(status -> {
            var rules=runner.pinActive();
            var captured=evidence.capture(body,rules,ComputeEvidenceService.now());
            JsonNode settings=read(store.jdbc().queryForObject("SELECT settings FROM rule_set WHERE id=?",String.class,rules.ruleSetId()));
            var choice=selector.select(captured,rules,settings);
            JdbcTemplate j=store.jdbc();
            long id=ComputeReleaseBridge.insert(j,"""
                    INSERT INTO target_group_selection(snapshot_id,rule_set_id,rule_version_id,definition_version,node_id,
                        request_id,job_id,gpu_count,spec_basis,target_group_id,target_group_code,rule_result,reason_code,
                        selection_status,actor,facts,output)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,captured.snapshot().id(),rules.ruleSetId(),choice.rule().ruleVersionId(),choice.rule().definitionVersion(),
                    captured.snapshot().nodeId(),captured.snapshot().requestId(),choice.jobId(),choice.gpuCount(),choice.specBasis(),
                    choice.targetGroupId(),choice.targetGroupCode(),choice.ruleResult().name(),choice.reasonCode(),
                    choice.selectionStatus(),captured.snapshot().actor(),choice.facts().toString(),choice.output().toString());
            for (long evidenceId : choice.evidenceIds()) j.update("""
                    INSERT INTO target_group_selection_evidence(selection_id,snapshot_id,evidence_id) VALUES(?,?,?)
                    """,id,captured.snapshot().id(),evidenceId);
            return detail(id);
        });
    }

    PageResult<Selection> list(int pageNum,int pageSize,Long requestId) {
        if (requestId!=null && requestId<=0) throw new BizException("requestId 必须是正整数");
        JdbcTemplate j=store.jdbc();
        String where=requestId==null?"":" WHERE t.request_id=?";
        var args=new ArrayList<Object>(); if(requestId!=null)args.add(requestId);
        long total=j.queryForObject("SELECT COUNT(*) FROM target_group_selection t"+where,Long.class,args.toArray());
        args.add(pageSize); args.add((pageNum-1L)*pageSize);
        return PageResult.of(j.query(SELECT+where+" ORDER BY t.id DESC LIMIT ? OFFSET ?",(r,i)->summary(r),args.toArray()),total,pageNum,pageSize);
    }

    Detail detail(long id) {
        JdbcTemplate j=store.jdbc();
        return j.query(SELECT+" WHERE t.id=?",rows->{
            if(!rows.next())throw new BizException("目标组选择记录不存在");
            var selection=summary(rows);
            JsonNode facts=read(rows.getString("facts")),output=read(rows.getString("output"));
            var refs=j.queryForList("SELECT evidence_id FROM target_group_selection_evidence WHERE selection_id=? ORDER BY evidence_id",Long.class,id);
            return new Detail(selection,facts,output,List.copyOf(refs),evidence.detail(selection.snapshotId()));
        },id);
    }

    private Selection summary(ResultSet r)throws SQLException {
        return new Selection(r.getLong("id"),r.getLong("request_id"),r.getLong("node_id"),r.getObject("job_id",Long.class),
                r.getInt("gpu_count"),r.getString("spec_basis"),r.getObject("target_group_id",Long.class),r.getString("target_group_code"),
                r.getString("selection_status"),Status.valueOf(r.getString("rule_result")),r.getString("reason_code"),
                r.getLong("rule_set_id"),r.getLong("rule_version_id"),r.getInt("rule_version"),r.getInt("definition_version"),
                r.getLong("snapshot_id"),r.getObject("as_of",LocalDateTime.class).toInstant(ZoneOffset.UTC),r.getString("actor"),false,false);
    }
    private JsonNode read(String value) {
        try{return json.readTree(value);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new BizException("目标选择 JSON 无法解析");}
    }
}
