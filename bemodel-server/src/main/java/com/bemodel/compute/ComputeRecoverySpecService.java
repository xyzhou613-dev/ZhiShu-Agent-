package com.bemodel.compute;
import com.bemodel.auth.CurrentUser;
import com.bemodel.common.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.util.*;
@Service @Profile("compute")
final class ComputeRecoverySpecService {
 private final ComputeDatabase.Store store;
 ComputeRecoverySpecService(ComputeDatabase.Store store){this.store=store;}
 List<Map<String,Object>> list(){return store.jdbc().queryForList("SELECT d.id,d.name,d.preemptible,d.group_id,g.group_code,h.id AS actual_hold_id,h.deployment_revision_id AS actual_revision_id,d.current_revision_id,s.id AS recovery_spec_id,s.vram_bytes_per_gpu,s.gpu_interconnect,s.harness_team_id FROM deployment d JOIN gpu_group g ON g.id=d.group_id LEFT JOIN hold_period h ON h.deployment_id=d.id AND h.ended_at IS NULL LEFT JOIN deployment_recovery_spec s ON s.id=(SELECT MAX(x.id) FROM deployment_recovery_spec x WHERE x.revision_id=h.deployment_revision_id) ORDER BY d.id");}
 Map<String,Object> register(long deployment,JsonNode body){if(body==null||!body.isObject()||!body.path("gpuInterconnect").isTextual()||!Set.of("NONE","NVLINK","PCIE").contains(body.path("gpuInterconnect").asText())||!body.path("declaration").isTextual()||body.path("declaration").asText().isBlank()||body.path("declaration").asText().length()>512)throw new BizException("请明确声明互联需求、资源依据及 harness 团队；不能使用缺省恢复值");
  for(String k:List.of("revisionId","vramBytesPerGpu","harnessTeamId"))if(!body.path(k).isIntegralNumber()||!body.path(k).canConvertToLong()||body.path(k).asLong()<=0)throw new BizException(k+" 必须为正整数");
  return store.transactions().execute(tx->{var j=store.jdbc();if(j.queryForObject("SELECT COUNT(*) FROM deployment_revision WHERE id=? AND deployment_id=?",Integer.class,body.path("revisionId").asLong(),deployment)!=1)throw new BizException("恢复修订不属于部署");if(j.queryForObject("SELECT COUNT(*) FROM team WHERE id=?",Integer.class,body.path("harnessTeamId").asLong())!=1)throw new BizException("harness 团队不存在");
   long id=ComputeReleaseBridge.insert(j,"INSERT INTO deployment_recovery_spec(revision_id,vram_bytes_per_gpu,gpu_interconnect,harness_team_id,declaration,declared_by) VALUES(?,?,?,?,?,?)",body.path("revisionId").asLong(),body.path("vramBytesPerGpu").asLong(),body.path("gpuInterconnect").asText(),body.path("harnessTeamId").asLong(),body.path("declaration").asText(),Objects.requireNonNullElse(CurrentUser.username(),"system"));return j.queryForMap("SELECT * FROM deployment_recovery_spec WHERE id=?",id);});
 }
}
