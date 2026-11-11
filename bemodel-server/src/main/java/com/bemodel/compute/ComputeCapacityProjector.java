package com.bemodel.compute;

import com.bemodel.common.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import static com.bemodel.compute.ComputeResourceMath.Bytes;
import static com.bemodel.compute.ComputeRuleEvaluator.Status;

/** Conditional forecasts from one sealed ledger, with no reservation or execution side effects. */
final class ComputeCapacityProjector {
    record Check(ComputeRuleRunner.ExecutionRule rule, Status result, String reasonCode, JsonNode facts, JsonNode output) {}
    record Projection(String mode, Long targetGroupId, String targetGroupCode, Status overallResult,
                      String feasibility, JsonNode output, List<Check> checks) {}
    private final ObjectMapper json;
    ComputeCapacityProjector(ObjectMapper json){this.json=json;}

    Projection project(ComputeEvidenceService.Detail captured,ComputeRuleRunner.Snapshot rules,JsonNode settings,JsonNode proposal) {
        try { return calculate(captured,rules,settings,proposal); }
        catch(ArithmeticException e){throw new BizException("容量投影数值越界或溢出");}
    }

    private Projection calculate(ComputeEvidenceService.Detail captured,ComputeRuleRunner.Snapshot rules,JsonNode settings,JsonNode proposal) {
        JsonNode ledger=captured.evidence().stream().filter(e->"PLATFORM_LEDGER".equals(e.source())).findFirst().orElseThrow().values();
        if(!captured.snapshot().sealed() || captured.snapshot().ruleSetId()!=rules.ruleSetId())throw new BizException("容量投影需要同一集合的已封存快照");
        String mode=proposal.path("mode").asText("PLAN"); boolean restore="RESTORE".equals(mode);
        var checks=new ArrayList<Check>();
        JsonNode node=ledger.path("node"),job=ledger.path("activeJob"),request=ledger.path("request");
        long memory=Math.subtractExact(number(node,"memory_capacity_bytes"),number(node,"memory_reserved_bytes"));
        if(memory<0)throw new BizException("节点系统保留内存超过容量");
        Bytes capacity=new Bytes(memory,number(node,"temporary_capacity_bytes"),number(node,"persistent_capacity_bytes"));
        JsonNode own=null,revision=null; Long targetId=null; String targetCode=null;
        JsonNode demand=job.isObject()?job.path("effective_resource_spec"):request.path("requested_resource_spec");
        long count=job.isObject()?number(job,"effective_gpu_count"):number(request,"requested_gpu_count");
        Bytes added; Long paused=null;
        var output=json.createObjectNode().put("mode",mode).put("formulaVersion",rules.formulaVersion())
                .put("authorizesExecution",false).put("queuePersisted",false).put("readyToExecute",false);
        if(restore) {
            long revisionId=proposal.path("restoreRevisionId").asLong();
            revision=rows(ledger.path("deploymentRevisions")).stream().filter(r->r.path("id").asLong()==revisionId).findFirst()
                    .orElseThrow(()->new BizException("恢复修订不属于该节点"));
            targetId=number(revision,"group_id"); added=bytes(revision);
            long deploymentId=number(revision,"deployment_id"); paused=deploymentId;
            long restoreGroup=targetId;
            count=groupCards(ledger,restoreGroup).size();
            output.put("restoreRevisionId",revisionId).put("restoreDeploymentId",deploymentId);
        } else {
            if(job.isObject() && !Set.of("PENDING","RUNNING").contains(job.path("status").asText()))throw new BizException("当前作业不处于待执行或运行状态");
            added=spec(demand);
            var ownRows=rows(ledger.path("jobAllocations"));
            if(!ownRows.isEmpty()) {
                own=ownRows.getFirst();
                if(own.path("node_id").asLong()!=captured.snapshot().nodeId() || !Set.of("PENDING","OCCUPIED").contains(own.path("status").asText()))
                    throw new BizException("当前作业已有其他节点或已关闭的分配，请使用后续作业重新规划");
                if(!added.equals(bytes(own)))throw new BizException("作业有效规格与已有预留不一致，请先核对账本");
                targetId=number(own,"group_id"); output.put("existingAllocationId",number(own,"id"));
                output.put("targetBasis","EXISTING_ALLOCATION");
            } else {
                var target=new ComputeTargetSelector(json).select(captured,rules,settings);
                targetId=target.targetGroupId(); output.set("targetSelection",target.output());
                checks.add(new Check(target.rule(),target.ruleResult(),target.reasonCode(),target.facts(),json.valueToTree(target.ruleResult()==Status.PASS?Map.of("targetGroup",target.targetGroupCode()):Map.of())));
                output.put("targetBasis","R-04");
            }
            if(proposal.hasNonNull("pausedDeploymentId")) {
                long id=proposal.path("pausedDeploymentId").asLong(); Long chosen=targetId;
                JsonNode baseline=rows(ledger.path("deploymentBaselines")).stream().filter(d->d.path("id").asLong()==id
                        && chosen!=null && d.path("group_id").asLong()==chosen).findFirst()
                        .orElseThrow(()->new BizException("拟暂停部署不是目标组的当前持有部署"));
                String task=job.isObject()?job.path("type_code").asText():request.path("type_code").asText();
                JsonNode group=findGroup(ledger,targetId);
                var f=json.createObjectNode().put("preemptionRequired",true).put("holderKind","DEPLOYMENT")
                        .put("taskType","TRAINING".equals(task)?"TRAIN":task).put("groupCode",group.path("group_code").asText())
                        .put("preemptible",baseline.path("preemptible").asBoolean())
                        .put("deploymentGpuCount",groupCards(ledger,targetId).size())
                        .put("requestPriority","TRAINING".equals(task)?settings.path("trainingPriority").asLong():0)
                        .put("deploymentPriority",settings.path("residentPriority").asLong());
                Check check=evaluate(rules,"R-13",f); checks.add(check);
                if(check.result()==Status.PASS)paused=id;
                output.put("proposedPauseDeploymentId",id).put("pauseAppliedInProjection",paused!=null).put("requiresStopConfirmation",true);
            }
        }
        JsonNode group=targetId==null?null:findGroup(ledger,targetId);
        if(group!=null)targetCode=group.path("group_code").asText();
        Bytes continuing=Bytes.zero(),reservations=Bytes.zero();
        for(JsonNode baseline:rows(ledger.path("deploymentBaselines")))if(paused==null || baseline.path("id").asLong()!=paused)continuing=continuing.plus(bytes(baseline));
        var included=new ArrayList<Long>();
        for(JsonNode a:rows(ledger.path("allocations"))) {
            if(!restore && own!=null && a.path("id").asLong()==own.path("id").asLong())continue;
            reservations=reservations.plus(bytes(a)); included.add(number(a,"id"));
        }
        Bytes projected=ComputeResourceMath.projection(continuing,reservations,added);
        var capacityFacts=json.createObjectNode();
        capacityFacts.set("capacityBytes",json.valueToTree(capacity.values()));
        capacityFacts.set(restore?"otherDeploymentBytes":"continuingDeploymentBytes",json.valueToTree(continuing.values()));
        capacityFacts.set("effectiveReservationBytes",json.valueToTree(reservations.values()));
        capacityFacts.set(restore?"restoreDeploymentBytes":"newReservationBytes",json.valueToTree(added.values()));
        output.set("capacityBytes",json.valueToTree(capacity.values()));
        output.set("continuingDeploymentBytes",json.valueToTree(continuing.values()));
        output.set("effectiveReservationBytes",json.valueToTree(reservations.values()));
        output.set(restore?"restoreDeploymentBytes":"newReservationBytes",json.valueToTree(added.values()));
        output.set("projectedBytes",json.valueToTree(projected.values())); output.put("withinCapacity",projected.within(capacity));
        output.set("includedAllocationIds",json.valueToTree(included));
        output.put("gpuCount",count).put("specBasis",restore?"DEPLOYMENT_REVISION":job.isObject()?"JOB":"REQUEST");
        var gpuFacts=json.createObjectNode().put("gpuCount",count);
        var cardFacts=gpuFacts.putArray("groupCards");
        var availableCards=targetId==null?List.<JsonNode>of():groupCards(ledger,targetId);
        var health=new HashMap<Long,Boolean>();
        for(JsonNode card:availableCards) {
            long id=number(card,"id");
            boolean healthy=captured.evidence().stream().filter(e->e.observationId()!=null && "GPU".equals(e.values().path("subjectKind").asText())
                    && e.values().path("subjectId").asLong()==id).anyMatch(e->e.values().path("usable").asBoolean()
                    && e.values().path("healthy").asBoolean() && ComputeResourceMath.evidenceValid(e.observedAt(),e.validUntil(),captured.snapshot().asOf(),e.values().path("maxAgeSeconds").asLong()));
            health.put(id,healthy); cardFacts.addObject().put("gpuId",id).put("healthy",healthy);
        }
        if(restore) {
            capacityFacts.set("groupCards",cardFacts); checks.add(evaluate(rules,"R-15",capacityFacts));
            checks.add(unknown(rules,"R-03","UNKNOWN_RESTORE_GPU_REQUIREMENTS",json.createObjectNode()));
            output.putNull("requiredVramBytesPerGpu").put("interconnectStatus","UNKNOWN");
        } else {
            checks.add(evaluate(rules,"R-02",capacityFacts));
            if(targetId==null) {
                checks.add(skipped(rules,"R-03"));checks.add(skipped(rules,"R-14"));
            } else {
                long vram=demand.path("vramBytesPerGpu").isIntegralNumber() && demand.path("vramBytesPerGpu").canConvertToLong()?demand.path("vramBytesPerGpu").asLong():0;
                var sorted=new ArrayList<>(availableCards);
                sorted.sort(Comparator.comparing((JsonNode c)->!(health.get(number(c,"id")) && vram>0 && c.path("vram_bytes").asLong()>=vram))
                        .thenComparingLong(c->c.path("gpu_index").asLong()));
                var used=new ArrayList<Long>();
                if(own!=null)for(JsonNode a:rows(ledger.path("allocationCards")))if(a.path("allocation_id").asLong()==own.path("id").asLong())used.add(number(a,"gpu_id"));
                if(used.isEmpty())for(JsonNode c:sorted)if(used.size()<count)used.add(number(c,"id"));
                gpuFacts.set("usedGpuIds",json.valueToTree(used));
                checks.add(evaluate(rules,"R-14",gpuFacts));
                var resourceFacts=json.createObjectNode();
                resourceFacts.put("vramBytesPerGpu",vram);var vrams=resourceFacts.putArray("selectedGpuVramBytes");
                boolean allKnown=true;
                for(long id:used) {
                    var c=availableCards.stream().filter(x->x.path("id").asLong()==id).findFirst();
                    if(c.isEmpty())allKnown=false;else vrams.add(number(c.get(),"vram_bytes"));
                }
                resourceFacts.set("requiredBytes",json.valueToTree(added.values()));
                resourceFacts.set("availableBytes",json.valueToTree(new Bytes(Math.max(0,capacity.memoryBytes()-continuing.memoryBytes()-reservations.memoryBytes()),
                        Math.max(0,capacity.temporaryBytes()-continuing.temporaryBytes()-reservations.temporaryBytes()),
                        Math.max(0,capacity.persistentBytes()-continuing.persistentBytes()-reservations.persistentBytes())).values()));
                String interconnect=demand.path("gpuInterconnect").asText("");
                resourceFacts.put("requiredInterconnect",interconnect).put("groupInterconnect","NONE".equals(interconnect)?"NONE":"UNKNOWN");
                if(vram<=0 || !allKnown)checks.add(unknown(rules,"R-03","UNKNOWN_VRAM_REQUIREMENT",resourceFacts));
                else if(!"NONE".equals(interconnect))checks.add(unknown(rules,"R-03","UNKNOWN_INTERCONNECT",resourceFacts));
                else checks.add(evaluate(rules,"R-03",resourceFacts));
                output.set("usedGpuIds",json.valueToTree(used));
                output.set("retainedGpuIds",json.valueToTree(availableCards.stream().map(c->number(c,"id")).filter(id->!used.contains(id)).toList()));
                if(vram>0)output.put("requiredVramBytesPerGpu",vram);else output.putNull("requiredVramBytesPerGpu");
                output.put("interconnectStatus","NONE".equals(interconnect)?"NOT_REQUIRED":"UNKNOWN");
            }
        }
        var freshFacts=json.createObjectNode().put("asOf",captured.snapshot().asOf().toString());
        var observations=freshFacts.putArray("evidence");
        for(var e:captured.evidence())if(e.observationId()!=null)observations.addObject().put("observedAt",e.observedAt().toString()).put("validUntil",e.validUntil().toString());
        Check freshness=evaluate(rules,"R-11",freshFacts);
        if(!captured.snapshot().manifest().path("usable").asBoolean())freshness=new Check(rule(rules,"R-11"),Status.FAIL,"QUEUE_STALE_EVIDENCE",freshFacts,captured.snapshot().manifest().path("issues"));
        checks.add(freshness);
        Status overall=checks.stream().anyMatch(c->c.result()==Status.ERROR)?Status.ERROR:checks.stream().anyMatch(c->c.result()==Status.FAIL)?Status.FAIL:Status.PASS;
        String feasibility=overall==Status.ERROR?"UNKNOWN":overall==Status.FAIL?"INFEASIBLE":"FEASIBLE";
        return new Projection(mode,targetId,targetCode,overall,feasibility,output,List.copyOf(checks));
    }

    private Check evaluate(ComputeRuleRunner.Snapshot rules,String code,JsonNode facts){
        var r=rule(rules,code);var result=new ComputeRuleEvaluator().evaluateSnapshot(code,r.ruleVersion(),r.definition(),facts);
        return new Check(r,result.result(),result.reasonCode(),facts,json.valueToTree(result.output()));
    }
    private Check unknown(ComputeRuleRunner.Snapshot rules,String code,String reason,JsonNode facts){return new Check(rule(rules,code),Status.ERROR,reason,facts,json.createObjectNode().put("status","UNKNOWN"));}
    private Check skipped(ComputeRuleRunner.Snapshot rules,String code){return new Check(rule(rules,code),Status.NOT_APPLICABLE,"NO_TARGET",json.createObjectNode(),json.createObjectNode());}
    private ComputeRuleRunner.ExecutionRule rule(ComputeRuleRunner.Snapshot rules,String code){return rules.rules().stream().filter(r->code.equals(r.ruleCode())).findFirst().orElseThrow();}
    private static JsonNode findGroup(JsonNode ledger,long id){return rows(ledger.path("groups")).stream().filter(g->g.path("id").asLong()==id).findFirst().orElseThrow(()->new BizException("目标卡组不属于节点快照"));}
    private static List<JsonNode> groupCards(JsonNode ledger,long id){return rows(ledger.path("cards")).stream().filter(c->c.path("group_id").asLong()==id).toList();}
    private static List<JsonNode> rows(JsonNode node){var list=new ArrayList<JsonNode>();node.forEach(list::add);return list;}
    private static long number(JsonNode row,String key){var v=row.path(key);if(!v.isIntegralNumber()||!v.canConvertToLong()||v.asLong()<0)throw new BizException("账本资源字段缺失或越界: "+key);return v.longValue();}
    private static Bytes bytes(JsonNode row){return new Bytes(number(row,"memory_bytes"),number(row,"temporary_bytes"),number(row,"persistent_bytes"));}
    private static Bytes spec(JsonNode row){return new Bytes(number(row,"memoryBytes"),row.has("temporaryBytes")?number(row,"temporaryBytes"):0,row.has("persistentBytes")?number(row,"persistentBytes"):0);}
}
