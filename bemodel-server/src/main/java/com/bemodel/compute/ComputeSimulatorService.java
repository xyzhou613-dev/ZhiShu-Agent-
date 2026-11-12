package com.bemodel.compute;

import com.bemodel.auth.CurrentUser;
import com.bemodel.common.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.*;
import java.util.*;
import static com.bemodel.compute.ComputeActionExecutor.*;

/** Durable synthetic hardware. It never changes the platform's holds, allocations, jobs or workflows. */
@Service @Profile("compute")
@ConditionalOnProperty(name="compute.simulator.enabled",havingValue="true")
final class ComputeSimulatorService {
    static final String SOURCE="A800_SIMULATOR_V1";
    static final Set<String> ACTIONS=Set.of("NOTIFY","DRAIN","STOP","START_JOB","STOP_JOB","RESTORE","HEALTH_CHECK","RESUME_TRAFFIC");
    private final ComputeDatabase.Store store;private final ObjectMapper json;
    private final ComputeRuntimeSettings runtime;private final ApplicationEventPublisher events;private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    ComputeSimulatorService(ComputeDatabase.Store store,ObjectMapper json,ComputeRuntimeSettings runtime,ApplicationEventPublisher events){this(store,json,runtime,events,Clock.systemUTC());}
    ComputeSimulatorService(ComputeDatabase.Store store,ObjectMapper json,ComputeRuntimeSettings runtime,ApplicationEventPublisher events,Clock clock){this.store=store;this.json=json;this.runtime=runtime;this.events=events;this.clock=clock;}

    Map<String,Object> info(){guard();return Map.of("simulation",true,"enabled",true,"source",SOURCE,"nodeKey",runtime.nodeKey(),"supportedActions",ACTIONS,"authorizesExecution",false);}
    JsonNode initialize(String nodeKey){guard();return store.transactions().execute(tx->{
        long node=node(nodeKey);store.jdbc().queryForMap("SELECT id FROM compute_node WHERE id=? FOR UPDATE",node);
        var old=store.jdbc().queryForList("SELECT state_json FROM compute_simulator_state WHERE node_id=? FOR UPDATE",node);
        if(!old.isEmpty())return read(old.getFirst().get("state_json"));
        var state=json.createObjectNode().put("simulation",true).put("source",SOURCE).put("bootstrapBasis","CURRENT_LEDGER_ONCE");
        var cards=state.putObject("gpus");var deployments=state.putObject("deployments");var jobs=state.putObject("jobs");
        for(var gpu:store.jdbc().queryForList("SELECT id,group_id FROM gpu_device WHERE node_id=? ORDER BY id",node))
            cards.putObject(gpu.get("id").toString()).put("groupId",number(gpu,"group_id")).put("healthy",true).putNull("owner");
        for(var hold:store.jdbc().queryForList("SELECT h.* FROM hold_period h JOIN gpu_group g ON g.id=h.group_id WHERE g.node_id=? AND h.ended_at IS NULL ORDER BY h.id FOR UPDATE",node)){
            if(hold.get("deployment_id")!=null){
                long id=number(hold,"deployment_id");if(hold.get("deployment_revision_id")==null)throw new BizException("模拟初始化缺少实际持有部署修订");
                var revision=store.jdbc().queryForMap("SELECT * FROM deployment_revision WHERE id=? AND deployment_id=? FOR UPDATE",hold.get("deployment_revision_id"),id);
                var row=runtimeRow(revision).put("revisionId",number(revision,"id")).put("acceptingTraffic",true).put("inFlightRequests",0);
                deployments.set(Long.toString(id),row);occupy(state,row,groupCards(state,number(hold,"group_id")),"DEPLOYMENT:"+id);
            }else{
                var allocation=store.jdbc().queryForMap("SELECT * FROM gpu_allocation WHERE id=? AND status='OCCUPIED' FOR UPDATE",hold.get("allocation_id"));
                long job=number(allocation,"job_id");var row=runtimeRow(allocation);jobs.set(Long.toString(job),row);
                occupy(state,row,store.jdbc().queryForList("SELECT gpu_id FROM allocation_gpu WHERE allocation_id=? ORDER BY gpu_id",Long.class,allocation.get("id")),"JOB:"+job);
            }
        }
        store.jdbc().update("INSERT INTO compute_simulator_state(node_id,state_json) VALUES(?,?)",node,state.toString());return state;
    });}
    JsonNode state(String nodeKey){guard();var rows=store.jdbc().queryForList("SELECT state_json FROM compute_simulator_state WHERE node_id=?",node(nodeKey));if(rows.isEmpty())throw new BizException("请先显式初始化模拟节点");return read(rows.getFirst().get("state_json"));}

    JsonNode configure(String nodeKey,JsonNode body){guard();fields(body,Set.of("gpus","deployments"));return store.transactions().execute(tx->{
        long node=node(nodeKey);var state=lockedState(node);
        if(body.has("gpus")){if(!body.path("gpus").isArray())throw new BizException("gpus 必须是数组");for(var patch:body.path("gpus")){
            fields(patch,Set.of("id","healthy"));long id=positive(patch,"id");if(!patch.path("healthy").isBoolean())throw new BizException("healthy 必须为布尔值");
            object(state.path("gpus"),id).set("healthy",patch.path("healthy"));
        }}
        if(body.has("deployments")){if(!body.path("deployments").isArray())throw new BizException("deployments 必须是数组");for(var patch:body.path("deployments")){
            fields(patch,Set.of("id","healthy","inFlightRequests"));var row=object(state.path("deployments"),positive(patch,"id"));
            if(patch.has("healthy")){if(!patch.path("healthy").isBoolean())throw new BizException("healthy 必须为布尔值");row.set("healthy",patch.path("healthy"));}
            if(patch.has("inFlightRequests"))row.put("inFlightRequests",integer(patch,"inFlightRequests",0,1_000_000));
        }}
        save(node,state);return state;
    });}

    JsonNode scenario(JsonNode body){guard();fields(body,Set.of("operationKey","mode","delayMs","failAttempts"));if(!body.path("operationKey").isTextual()||!body.path("mode").isTextual())throw new BizException("operationKey 和 mode 必须为文本");String key=key(body.path("operationKey").asText());
        String mode=body.path("mode").asText();if(!Set.of("SUCCESS","FAILURE","FAIL_THEN_SUCCESS","TIMEOUT").contains(mode))throw new BizException("模拟模式必须为 SUCCESS/FAILURE/FAIL_THEN_SUCCESS/TIMEOUT");
        var scenario=json.createObjectNode().put("mode",mode).put("delayMs",body.has("delayMs")?integer(body,"delayMs",0,300_000):0)
                .put("failAttempts",body.has("failAttempts")?integer(body,"failAttempts",1,10):1);
        return store.transactions().execute(tx->{
            // Serializes configuration with first acceptance; operation creation uses the same node lock.
            lockedState(node(runtime.nodeKey()));
            if(!store.jdbc().queryForList("SELECT operation_key FROM compute_simulator_operation WHERE operation_key=? FOR UPDATE",key).isEmpty())throw new BizException("操作已接收，不能修改其模拟场景");
            store.jdbc().update("INSERT INTO compute_simulator_scenario(operation_key,scenario_json,created_by) VALUES(?,?,?) ON DUPLICATE KEY UPDATE scenario_json=VALUES(scenario_json),created_by=VALUES(created_by),updated_at=UTC_TIMESTAMP(6)",key,scenario.toString(),Objects.requireNonNullElse(CurrentUser.username(),"system"));return scenario;
        });}

    Reply execute(Command command){guard();return store.transactions().execute(tx->{
        long node=validate(command);var state=lockedState(node);
        int managerAttempt=store.jdbc().queryForObject("SELECT attempt_count FROM execution_action WHERE id=?",Integer.class,command.actionId());
        var rows=store.jdbc().queryForList("SELECT * FROM compute_simulator_operation WHERE operation_key=? FOR UPDATE",command.operationKey());
        if(rows.isEmpty()){
            var scenarios=store.jdbc().queryForList("SELECT scenario_json FROM compute_simulator_scenario WHERE operation_key=? FOR UPDATE",command.operationKey());
            JsonNode scenario=scenarios.isEmpty()?json.createObjectNode().put("mode","SUCCESS").put("delayMs",0).put("failAttempts",1):read(scenarios.getFirst().get("scenario_json"));
            store.jdbc().update("INSERT INTO compute_simulator_operation(operation_key,action_id,node_id,command_json,scenario_json,ready_at,manager_attempt) VALUES(?,?,?,?,?,?,?)",
                    command.operationKey(),command.actionId(),node,write(command),scenario.toString(),utc(now().plusMillis(scenario.path("delayMs").asLong())),managerAttempt);
        }else{
            var row=rows.getFirst();same(row,command);
            store.jdbc().update("UPDATE compute_simulator_operation SET send_count=send_count+1 WHERE operation_key=?",command.operationKey());
            if("FAILED".equals(row.get("status")) && bool(row.get("retry_allowed")) && managerAttempt>number(row,"manager_attempt")){
                JsonNode scenario=read(row.get("scenario_json"));
                store.jdbc().update("UPDATE compute_simulator_operation SET status='ACCEPTED',attempt_count=attempt_count+1,manager_attempt=?,retry_allowed=FALSE,receipt=NULL,error_code=NULL,ready_at=?,updated_at=? WHERE operation_key=?",managerAttempt,utc(now().plusMillis(scenario.path("delayMs").asLong())),utc(now()),command.operationKey());
            }
        }
        var operation=complete(command.operationKey(),state,node);save(node,state);
        if("TIMEOUT".equals(read(operation.get("scenario_json")).path("mode").asText()))return unknown(command,"SIMULATED_TIMEOUT");
        return reply(operation,command);
    });}
    Reply query(Command command){guard();return store.transactions().execute(tx->{
        long node=validate(command);var state=lockedState(node);
        var rows=store.jdbc().queryForList("SELECT * FROM compute_simulator_operation WHERE operation_key=? FOR UPDATE",command.operationKey());
        if(rows.isEmpty())return new Reply(State.FAILED,base(command).put("status","FAILED"),"SIMULATED_NOT_ACCEPTED",true);
        same(rows.getFirst(),command);store.jdbc().update("UPDATE compute_simulator_operation SET query_count=query_count+1 WHERE operation_key=?",command.operationKey());
        var row=complete(command.operationKey(),state,node);save(node,state);return reply(row,command);
    });}

    Map<String,Object> operation(String operationKey){guard();var rows=store.jdbc().queryForList("SELECT operation_key,action_id,node_id,status,scenario_json,attempt_count,send_count,query_count,effect_count,ready_at,receipt,error_code,retry_allowed FROM compute_simulator_operation WHERE operation_key=? AND node_id=?",key(operationKey),node(runtime.nodeKey()));
        if(rows.isEmpty())throw new BizException("模拟操作不存在");var row=rows.getFirst();for(String field:List.of("scenario_json","receipt"))if(row.get(field)!=null)row.put(field,read(row.get(field)));row.put("ready_at",instant(row.get("ready_at")));return row;}

    Map<String,Object> observe(String nodeKey,JsonNode body){guard();fields(body,Set.of("validForSeconds","failGpuIds","failNode"));
        int ttl=body.has("validForSeconds")?integer(body,"validForSeconds",1,300):30;
        if(body.has("failNode")&&!body.path("failNode").isBoolean())throw new BizException("failNode 必须为布尔值");
        Set<Long> failed=new HashSet<>();if(body.has("failGpuIds")){if(!body.path("failGpuIds").isArray())throw new BizException("failGpuIds 必须是数组");for(JsonNode id:body.path("failGpuIds")){if(!id.isIntegralNumber()||!id.canConvertToLong()||id.asLong()<=0||!failed.add(id.asLong()))throw new BizException("失败 GPU 编号必须为不重复正整数");}}
        var result=store.transactions().execute(tx->{long node=node(nodeKey);var state=lockedState(node);
            for(var key:store.jdbc().queryForList("SELECT operation_key FROM compute_simulator_operation WHERE node_id=? AND status='ACCEPTED' AND ready_at<=? ORDER BY ready_at,operation_key FOR UPDATE",String.class,node,utc(now())))complete(key,state,node);
            save(node,state);Instant at=now();var ids=new ArrayList<Long>();
            var cards=store.jdbc().queryForList("SELECT id,gpu_uuid FROM gpu_device WHERE node_id=? ORDER BY id",node);
            for(long id:failed)if(cards.stream().noneMatch(c->number(c,"id")==id))throw new BizException("失败 GPU 不属于模拟节点");
            for(var card:cards){long id=number(card,"id");var value=object(state.path("gpus"),id);var sample=json.createObjectNode().put("simulation",true).put("healthy",value.path("healthy").asBoolean()).put("occupied",!value.path("owner").isNull());
                ids.add(observation(node,"GPU",id,card.get("gpu_uuid").toString(),at,ttl,failed.contains(id)?null:sample));}
            var used=usage(state);ids.add(observation(node,"NODE",node,nodeKey,at,ttl,body.path("failNode").asBoolean()?null:used));
            return Map.<String,Object>of("simulation",true,"source",SOURCE,"nodeKey",nodeKey,"observedAt",at,"validUntil",at.plusSeconds(ttl),"observationIds",ids);
        });events.publishEvent(new ComputeQueueScheduler.ResourceChanged(nodeKey));return result;}

    private Map<String,Object> complete(String key,ObjectNode state,long node){
        var row=store.jdbc().queryForMap("SELECT * FROM compute_simulator_operation WHERE operation_key=? FOR UPDATE",key);
        if(!"ACCEPTED".equals(row.get("status")) || instant(row.get("ready_at")).isAfter(now()))return row;
        Command command=command(row.get("command_json"));JsonNode scenario=read(row.get("scenario_json"));String mode=scenario.path("mode").asText();
        boolean injected="FAILURE".equals(mode)||"FAIL_THEN_SUCCESS".equals(mode)&&number(row,"attempt_count")<=scenario.path("failAttempts").asLong();
        var receipt=base(command);String error=null;boolean retry=false;
        ObjectNode candidate=state.deepCopy();
        if(injected){error="SIMULATED_FAILURE";retry="FAIL_THEN_SUCCESS".equals(mode);}
        else try{apply(command,candidate,receipt);if(Set.of("START_JOB","RESTORE").contains(command.actionType())){var limits=store.jdbc().queryForMap("SELECT * FROM compute_node WHERE id=?",node);var used=usage(candidate);
            if(used.path("memoryUsedBytes").asLong()>Math.subtractExact(number(limits,"memory_capacity_bytes"),number(limits,"memory_reserved_bytes")) || used.path("temporaryUsedBytes").asLong()>number(limits,"temporary_capacity_bytes") || used.path("persistentUsedBytes").asLong()>number(limits,"persistent_capacity_bytes"))throw new BizException("SIMULATED_CAPACITY_EXCEEDED");
        }
        }catch(BizException e){error=e.getMessage();retry=Set.of("SIMULATED_DRAIN_REQUIRED","SIMULATED_UNHEALTHY","SIMULATED_GPU_BUSY","SIMULATED_HANDOVER_REQUIRED","SIMULATED_CAPACITY_EXCEEDED","SIMULATED_RESTORE_REQUIRES_STOP").contains(error);}
        String status=error==null?"SUCCEEDED":"FAILED";if(error!=null)receipt=base(command);receipt.put("status",status).put("confirmedAt",now().toString());if(error!=null)receipt.put("errorCode",error);else {state.removeAll();state.setAll(candidate);}
        store.jdbc().update("UPDATE compute_simulator_operation SET status=?,receipt=?,error_code=?,retry_allowed=?,effect_count=?,updated_at=? WHERE operation_key=?",status,receipt.toString(),error,retry,error==null?1:0,utc(now()),key);
        store.jdbc().update("INSERT INTO resource_event(source,event_type,subject_kind,subject_id,causing_decision_id,action_id,occurred_at,impact_scope,payload) VALUES(?,'SIMULATED_ACTION_RESULT',?,?,?,?,?,?,?)",SOURCE,command.targetKind(),command.targetId(),command.decisionId(),command.actionId(),utc(now()),json.createObjectNode().put("simulation",true).put("nodeId",node).toString(),receipt.toString());
        return store.jdbc().queryForMap("SELECT * FROM compute_simulator_operation WHERE operation_key=?",key);
    }
    private void apply(Command c,ObjectNode state,ObjectNode receipt){
        if("NOTIFY".equals(c.actionType())){
            var recipients=c.input().path("recipients");if(!recipients.isArray()||recipients.isEmpty()||recipients.size()>100)throw new BizException("SIMULATED_INVALID_RECIPIENTS");
            var values=new LinkedHashSet<String>();for(var recipient:recipients){if(!recipient.isTextual()||!recipient.asText().matches("(employee|team):[^\\s:]{1,100}"))throw new BizException("SIMULATED_INVALID_RECIPIENTS");values.add(recipient.asText());}
            receipt.set("recipients",json.valueToTree(values));receipt.put("delivered",true).put("channel","SIMULATED");return;
        }
        if("START_JOB".equals(c.actionType())){
            var allocations=store.jdbc().queryForList("""
                    SELECT a.* FROM gpu_allocation a
                    JOIN hold_period h ON h.allocation_id=a.id AND h.group_id=a.group_id AND h.ended_at IS NULL
                    JOIN gpu_group g ON g.id=a.group_id AND g.current_hold_id=h.id
                    WHERE a.job_id=? AND a.status='OCCUPIED' AND g.pending_allocation_id IS NULL
                    """,c.targetId());
            if(allocations.size()!=1)throw new BizException("SIMULATED_HANDOVER_REQUIRED");var allocation=allocations.getFirst();
            var gpus=store.jdbc().queryForList("SELECT gpu_id FROM allocation_gpu WHERE allocation_id=? ORDER BY gpu_id",Long.class,allocation.get("id"));
            int count=store.jdbc().queryForObject("SELECT effective_gpu_count FROM compute_job WHERE id=?",Integer.class,c.targetId());if(gpus.size()!=count)throw new BizException("SIMULATED_GPU_MEMBERSHIP_INVALID");
            var row=runtimeRow(allocation);occupy(state,row,gpus,"JOB:"+c.targetId());((ObjectNode)state.path("jobs")).set(Long.toString(c.targetId()),row);receipt.put("running",true);return;
        }
        if("STOP_JOB".equals(c.actionType())){var jobs=(ObjectNode)state.path("jobs");var row=jobs.get(Long.toString(c.targetId()));
            if(row!=null)release(state,(ObjectNode)row,"JOB:"+c.targetId());receipt.put("running",false).put("gpuReleased",true);return;}
        var deployment=object(state.path("deployments"),c.targetId());
        switch(c.actionType()){
            case "DRAIN" -> {if(!deployment.path("running").asBoolean())throw new BizException("SIMULATED_DEPLOYMENT_STOPPED");deployment.put("acceptingTraffic",false);receipt.put("inFlightRequests",deployment.path("inFlightRequests").asLong()).put("acceptingTraffic",false);}
            case "STOP" -> {if(deployment.path("acceptingTraffic").asBoolean()||deployment.path("inFlightRequests").asLong()!=0)throw new BizException("SIMULATED_DRAIN_REQUIRED");release(state,deployment,"DEPLOYMENT:"+c.targetId());receipt.put("gpuReleased",true).put("running",false);}
            case "RESTORE" -> {
                if(deployment.path("running").asBoolean())throw new BizException("SIMULATED_RESTORE_REQUIRES_STOP");
                var revision=store.jdbc().queryForList("SELECT r.* FROM workflow_run w JOIN deployment_revision r ON r.id=w.restore_revision_id WHERE w.id=? AND r.deployment_id=?",c.workflowId(),c.targetId());
                if(revision.size()!=1)throw new BizException("SIMULATED_RESTORE_REVISION_MISSING");
                var row=runtimeRow(revision.getFirst()).put("revisionId",number(revision.getFirst(),"id")).put("acceptingTraffic",false).put("inFlightRequests",0);
                occupy(state,row,groupCards(state,store.jdbc().queryForObject("SELECT group_id FROM deployment WHERE id=?",Long.class,c.targetId())),"DEPLOYMENT:"+c.targetId());
                ((ObjectNode)state.path("deployments")).set(Long.toString(c.targetId()),row);receipt.put("restoreRevisionId",number(revision.getFirst(),"id")).put("running",true);
            }
            case "HEALTH_CHECK", "RESUME_TRAFFIC" -> {
                if(!deployment.path("running").asBoolean()||!deployment.path("healthy").asBoolean())throw new BizException("SIMULATED_UNHEALTHY");
                for(var gpu:deployment.path("gpuIds"))if(!object(state.path("gpus"),gpu.asLong()).path("healthy").asBoolean())throw new BizException("SIMULATED_UNHEALTHY");
                receipt.put("healthy",true);if("RESUME_TRAFFIC".equals(c.actionType())){deployment.put("acceptingTraffic",true);receipt.put("acceptingTraffic",true);}
            }
            default -> throw new BizException("SIMULATED_UNSUPPORTED_ACTION");
        }
    }
    private long validate(Command c){
        if(c==null||!ACTIONS.contains(c.actionType()))throw new BizException("模拟器不支持该动作");key(c.operationKey());
        var rows=store.jdbc().queryForList("SELECT a.*,g.node_id FROM execution_action a JOIN workflow_run w ON w.id=a.workflow_run_id JOIN gpu_group g ON g.id=w.group_id WHERE a.id=?",c.actionId());
        if(rows.size()!=1)throw new BizException("模拟动作不存在");var a=rows.getFirst();long node=node(runtime.nodeKey());
        if(number(a,"node_id")!=node||number(a,"workflow_run_id")!=c.workflowId()||number(a,"decision_id")!=c.decisionId()||number(a,"target_id")!=c.targetId()||!c.operationKey().equals(a.get("idempotency_key"))||!c.actionType().equals(a.get("action_type"))||!c.targetKind().equals(a.get("target_kind"))||!Objects.equals(read(a.get("input_json")),read(c.input())))throw new BizException("模拟命令与已登记动作不一致");
        return node;
    }
    private void same(Map<String,Object> row,Command c){if(!c.operationKey().equals(row.get("operation_key"))||!Objects.equals(read(row.get("command_json")),read(write(c))))throw new BizException("模拟操作编号对应的内容不同");}
    private Reply reply(Map<String,Object> row,Command c){if("ACCEPTED".equals(row.get("status")))return unknown(c,"SIMULATED_DELAY");return new Reply(State.valueOf(row.get("status").toString()),read(row.get("receipt")),(String)row.get("error_code"),bool(row.get("retry_allowed")));}
    private Reply unknown(Command c,String code){return new Reply(State.UNKNOWN,base(c).put("status","UNKNOWN"),code,false);}
    private ObjectNode base(Command c){return json.createObjectNode().put("simulation",true).put("source",SOURCE).put("operationKey",c.operationKey()).put("actionId",c.actionId()).put("actionType",c.actionType()).put("targetKind",c.targetKind()).put("targetId",c.targetId());}
    private ObjectNode runtimeRow(Map<String,Object> row){return json.createObjectNode().put("running",true).put("healthy",true).put("memoryBytes",number(row,"memory_bytes")).put("temporaryBytes",number(row,"temporary_bytes")).put("persistentBytes",number(row,"persistent_bytes"));}
    private void occupy(ObjectNode state,ObjectNode row,List<Long> gpus,String owner){if(gpus.isEmpty())throw new BizException("SIMULATED_GPU_MEMBERSHIP_INVALID");for(long id:gpus){var gpu=object(state.path("gpus"),id);if(!gpu.path("healthy").asBoolean())throw new BizException("SIMULATED_UNHEALTHY");if(!gpu.path("owner").isNull()&&!owner.equals(gpu.path("owner").asText()))throw new BizException("SIMULATED_GPU_BUSY");}
        row.set("gpuIds",json.valueToTree(gpus));for(long id:gpus)object(state.path("gpus"),id).put("owner",owner);}
    private void release(ObjectNode state,ObjectNode row,String owner){for(var gpu:state.path("gpus"))if(owner.equals(gpu.path("owner").asText()))((ObjectNode)gpu).putNull("owner");row.put("running",false);}
    private List<Long> groupCards(JsonNode state,long group){var ids=new ArrayList<Long>();state.path("gpus").fields().forEachRemaining(e->{if(e.getValue().path("groupId").asLong()==group)ids.add(Long.parseLong(e.getKey()));});return ids;}
    private ObjectNode usage(JsonNode state){long memory=0,temp=0,persistent=0;for(String kind:List.of("deployments","jobs"))for(var row:state.path(kind)){if(row.path("running").asBoolean())memory=Math.addExact(memory,row.path("memoryBytes").asLong());temp=Math.addExact(temp,row.path("temporaryBytes").asLong());persistent=Math.addExact(persistent,row.path("persistentBytes").asLong());}return json.createObjectNode().put("simulation",true).put("memoryUsedBytes",memory).put("temporaryUsedBytes",temp).put("persistentUsedBytes",persistent);}
    private long observation(long node,String kind,long id,String sourceKey,Instant at,int ttl,JsonNode value){return ComputeReleaseBridge.insert(store.jdbc(),"INSERT INTO resource_observation(node_id,source_subject_kind,source_subject_key,subject_kind,subject_id,source,observed_at,valid_until,status,values_json,error_detail) VALUES(?,?,?,?,?,?,?,?,?,?,?)",node,kind,sourceKey,kind,id,SOURCE,utc(at),utc(at.plusSeconds(ttl)),value==null?"ERROR":"OK",value==null?null:value.toString(),value==null?"SIMULATED_COLLECTION_FAILURE":null);}
    private void save(long node,ObjectNode state){store.jdbc().update("UPDATE compute_simulator_state SET state_json=?,updated_at=? WHERE node_id=?",state.toString(),utc(now()),node);}
    private ObjectNode lockedState(long node){var rows=store.jdbc().queryForList("SELECT state_json FROM compute_simulator_state WHERE node_id=? FOR UPDATE",node);if(rows.isEmpty())throw new BizException("请先显式初始化模拟节点");return (ObjectNode)read(rows.getFirst().get("state_json"));}
    private long node(String key){if(!runtime.nodeKey().equals(key))throw new BizException("仅允许操作配置中的模拟节点");var ids=store.jdbc().queryForList("SELECT id FROM compute_node WHERE node_key=?",Long.class,key);if(ids.size()!=1)throw new BizException("模拟节点不存在");return ids.getFirst();}
    private void guard(){if(!runtime.simulation())throw new BizException("模拟器仅允许 SIMULATION 模式");if(TransactionSynchronizationManager.isActualTransactionActive())throw new BizException("模拟器须在外层事务结束后调用");}
    private JsonNode read(Object value){if(value==null)return null;try{return json.readTree(value instanceof JsonNode n?n.toString():value.toString());}catch(Exception e){throw new BizException("模拟 JSON 无效");}}
    private Command command(Object value){try{return json.treeToValue(read(value),Command.class);}catch(Exception e){throw new BizException("模拟命令损坏");}}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new BizException("模拟命令无法序列化");}}
    private ObjectNode object(JsonNode parent,long id){var n=parent.path(Long.toString(id));if(!n.isObject())throw new BizException("SIMULATED_SUBJECT_NOT_FOUND");return (ObjectNode)n;}
    private static void fields(JsonNode body,Set<String> allowed){if(body==null||!body.isObject()||body.toString().length()>65536)throw new BizException("模拟输入必须为不超过 64K 的对象");body.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw new BizException("未知模拟字段: "+k);});}
    private static String key(String value){if(value==null||!value.matches("[A-Za-z0-9_.:/-]{1,128}"))throw new BizException("模拟操作编号无效");return value;}
    private static int integer(JsonNode n,String key,int min,int max){var v=n.path(key);if(!v.isIntegralNumber()||!v.canConvertToInt()||v.asInt()<min||v.asInt()>max)throw new BizException(key+" 整数范围为 "+min+"～"+max);return v.asInt();}
    private static long positive(JsonNode n,String key){var v=n.path(key);if(!v.isIntegralNumber()||!v.canConvertToLong()||v.asLong()<=0)throw new BizException(key+" 必须为正整数");return v.asLong();}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static boolean bool(Object value){return value instanceof Boolean b?b:((Number)value).intValue()!=0;}
    private Instant now(){return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
    private static Instant instant(Object value){return ((LocalDateTime)value).toInstant(ZoneOffset.UTC);}
    private static LocalDateTime utc(Instant value){return LocalDateTime.ofInstant(value,ZoneOffset.UTC);}
}
