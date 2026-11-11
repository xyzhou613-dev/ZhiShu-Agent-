package com.bemodel.compute;

import com.bemodel.common.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import static com.bemodel.compute.ComputeResourceMath.*;

/** Read-only forecasts from the current authoritative ledger. A forecast cannot reserve capacity. */
@Service @Profile("compute")
final class ComputeCapacityQuery {
    private final ComputeDatabase.Store store;
    private final ObjectMapper json;
    ComputeCapacityQuery(ComputeDatabase.Store store,ObjectMapper json){this.store=store;this.json=json;}
    Map<String,Object> preview(String nodeKey,Long jobId,Long pausedDeploymentId,Long restoreRevisionId) {
        var tx=store.transactions();
        tx.setReadOnly(true);
        return tx.execute(status->calculate(nodeKey,jobId,pausedDeploymentId,restoreRevisionId));
    }
    private Map<String,Object> calculate(String nodeKey,Long jobId,Long pausedDeploymentId,Long restoreRevisionId) {
        var j=store.jdbc();
        var nodes=j.queryForList("SELECT * FROM compute_node WHERE node_key=?",nodeKey);
        if(nodes.size()!=1) throw new BizException("节点不存在");
        var node=nodes.getFirst();long nodeId=number(node,"id");
        long memory=Math.subtractExact(number(node,"memory_capacity_bytes"),number(node,"memory_reserved_bytes"));
        if(memory<0) throw new BizException("节点系统保留内存超过容量，请先核对配置");
        var capacity=new Bytes(memory,number(node,"temporary_capacity_bytes"),number(node,"persistent_capacity_bytes"));
        var deployments=j.queryForList("SELECT r.*,d.group_id,d.preemptible,g.group_code,(SELECT COUNT(*) FROM gpu_device x WHERE x.group_id=g.id) AS card_count FROM hold_period h JOIN deployment_revision r ON r.id=h.deployment_revision_id JOIN deployment d ON d.id=r.deployment_id JOIN gpu_group g ON g.id=h.group_id WHERE g.node_id=? AND h.ended_at IS NULL",nodeId);
        var allocations=j.queryForList("SELECT a.* FROM gpu_allocation a JOIN gpu_group g ON g.id=a.group_id WHERE g.node_id=? AND a.status IN ('PENDING','OCCUPIED') ORDER BY a.id",nodeId);
        Map<String,Object> job=null;
        Bytes added=Bytes.zero();
        Long newAllocationId=null;
        if(jobId!=null) {
            var jobs=j.queryForList("SELECT x.*,t.type_code,t.priority FROM compute_job x JOIN task_type t ON t.id=x.task_type_id WHERE x.id=?",jobId);
            if(jobs.size()!=1) throw new BizException("作业不存在"); job=jobs.getFirst();
            if(!"PENDING".equals(job.get("status")) && !"RUNNING".equals(job.get("status"))) throw new BizException("该作业不处于待执行或运行状态");
            var own=j.queryForList("SELECT a.*,g.node_id FROM gpu_allocation a JOIN gpu_group g ON g.id=a.group_id WHERE a.job_id=?",jobId);
            if(!own.isEmpty()) {
                var a=own.getFirst();
                if(number(a,"node_id")!=nodeId || !Set.of("PENDING","OCCUPIED").contains(a.get("status"))) throw new BizException("作业已有其他节点或已关闭的分配，不可重复预测为新分配");
                newAllocationId=number(a,"id");added=bytes(a);
            } else added=spec(read(job.get("effective_resource_spec").toString()));
        }
        if(pausedDeploymentId!=null) {
            Map<String,Object> candidate=deployments.stream().filter(d->number(d,"deployment_id")==pausedDeploymentId).findFirst().orElseThrow(()->new BizException("待暂停部署不在该节点的当前持有账本中"));
            boolean registered=Boolean.TRUE.equals(candidate.get("preemptible")) || candidate.get("preemptible") instanceof Number n && n.intValue()==1;
            if(job==null || !preemptible("DEPLOYMENT","TRAINING".equals(job.get("type_code"))?"TRAIN":job.get("type_code").toString(),candidate.get("group_code").toString(),number(candidate,"card_count"),registered,number(job,"priority"),1))
                throw new BizException("该作业不能按 R-13 暂停指定部署");
        }
        Bytes continuing=Bytes.zero(),effective=Bytes.zero();
        for(var d:deployments) if(pausedDeploymentId==null || number(d,"deployment_id")!=pausedDeploymentId) continuing=continuing.plus(bytes(d));
        for(var a:allocations) if(newAllocationId==null || number(a,"id")!=newAllocationId) effective=effective.plus(bytes(a));
        Bytes projected=projection(continuing,effective,added);
        var result=new LinkedHashMap<String,Object>();
        result.put("formulaVersion",VERSION); result.put("ledgerReadAt",Instant.now());result.put("ledgerIsHistorical",false);
        result.put("authorizesExecution",false);result.put("nodeKey",nodeKey);result.put("capacityBytes",capacity);
        result.put("continuingDeploymentBytes",continuing);result.put("effectiveReservationBytes",effective);
        result.put("newReservationBytes",added);result.put("newAllocationIdExcludedFromExisting",newAllocationId);
        result.put("projectedBytes",projected);result.put("withinCapacity",projected.within(capacity));
        result.put("requiresStopConfirmation",pausedDeploymentId!=null);
        result.put("includedAllocationIds",allocations.stream().map(a->a.get("id")).toList());
        result.put("gpuDemand",job==null?null:gpuDemand(job,nodeId));
        if(restoreRevisionId!=null) {
            var revisions=j.queryForList("SELECT r.* FROM deployment_revision r JOIN deployment d ON d.id=r.deployment_id JOIN gpu_group g ON g.id=d.group_id WHERE r.id=? AND g.node_id=?",restoreRevisionId,nodeId);
            if(revisions.size()!=1) throw new BizException("恢复修订不属于该节点");
            var restore=revisions.getFirst(); Bytes other=Bytes.zero(),allReservations=Bytes.zero();
            for(var d:deployments) if(number(d,"deployment_id")!=number(restore,"deployment_id")) other=other.plus(bytes(d));
            for(var a:allocations) allReservations=allReservations.plus(bytes(a));
            var restored=restoreProjection(other,allReservations,bytes(restore));
            result.put("restore",Map.of("revisionId",restoreRevisionId,"otherDeploymentBytes",other,"effectiveReservationBytes",allReservations,"restoreDeploymentBytes",bytes(restore),"projectedBytes",restored,"withinCapacity",restored.within(capacity),"requiresWholeGroupHealthCheck",true));
        }
        return result;
    }
    private Map<String,Object> gpuDemand(Map<String,Object> job,long nodeId) {
        JsonNode spec=read(job.get("effective_resource_spec").toString());
        Object vram=spec.path("vramBytesPerGpu").isIntegralNumber()&&spec.path("vramBytesPerGpu").canConvertToLong()?spec.path("vramBytesPerGpu").longValue():"UNKNOWN";
        var demand=new LinkedHashMap<String,Object>(); demand.put("gpuCount",job.get("effective_gpu_count"));demand.put("requiredVramBytesPerGpu",vram);
        demand.put("groupCards",store.jdbc().queryForList("SELECT g.group_code,d.gpu_uuid,d.vram_bytes FROM gpu_device d JOIN gpu_group g ON g.id=d.group_id WHERE g.node_id=? ORDER BY g.id,d.gpu_index",nodeId));
        demand.put("interconnect",spec.path("gpuInterconnect").isTextual()?spec.path("gpuInterconnect").asText():"UNKNOWN");
        demand.put("fitVerified",false);return demand;
    }
    private Bytes bytes(Map<String,Object> row){return new Bytes(number(row,"memory_bytes"),number(row,"temporary_bytes"),number(row,"persistent_bytes"));}
    private long number(Map<String,Object> row,String key){Object v=row.get(key);if(!(v instanceof Number n)||n.longValue()<0) throw new BizException("账本资源字段缺失或越界: "+key);return n.longValue();}
    private Bytes spec(JsonNode spec){return new Bytes(value(spec,"memoryBytes",true),value(spec,"temporaryBytes",false),value(spec,"persistentBytes",false));}
    private long value(JsonNode spec,String key,boolean required){var v=spec.path(key);if(v.isMissingNode()&&!required)return 0;if(!v.isIntegralNumber()||!v.canConvertToLong()||v.longValue()<0)throw new BizException("作业资源规格缺失或非法: "+key);return v.longValue();}
    private JsonNode read(String value){try{return json.readTree(value);}catch(Exception e){throw new BizException("作业资源规格不是合法 JSON");}}
}
