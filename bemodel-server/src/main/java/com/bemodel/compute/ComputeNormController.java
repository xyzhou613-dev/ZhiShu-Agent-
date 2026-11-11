package com.bemodel.compute;

import com.bemodel.auth.CurrentUser;
import com.bemodel.common.BizException;
import com.bemodel.common.Result;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@Profile("compute")
@RequestMapping("/api/compute")
final class ComputeNormController {
    private final JdbcTemplate platform;
    private final ComputeDatabase.Store business;
    ComputeNormController(JdbcTemplate platform, ComputeDatabase.Store business) {
        this.platform=platform; this.business=business;
    }
    @GetMapping("/action-contracts")
    Result<?> contracts() {
        return Result.ok(platform.queryForList("SELECT action_code,name,status,version,contract_json FROM bm_action WHERE LEFT(action_code,8)='COMPUTE_' ORDER BY action_code"));
    }
    @GetMapping("/constraints")
    Result<?> constraints() {
        return Result.ok(platform.queryForList("SELECT axiom_code,description,status,enforcement_json FROM bm_axiom WHERE axiom_code REGEXP '^COMPUTE_I[0-9]{2}$' ORDER BY axiom_code"));
    }
    @GetMapping("/actions/results")
    Result<?> results(@RequestParam(required=false) Long workflowId, @RequestParam(required=false) String operationKey) {
        if (!CurrentUser.hasAnyRole("ADMIN","EDITOR")) throw new BizException("动作回执仅供管理员和编辑员查询");
        if (workflowId == null && (operationKey == null || operationKey.isBlank())) throw new BizException("请提供流程编号或固定操作编号");
        if (workflowId != null && workflowId<=0 || operationKey != null && operationKey.length()>128) throw new BizException("查询参数无效");
        String sql="SELECT id,workflow_run_id,decision_id,action_type,target_kind,target_id,idempotency_key,status,attempt_count,started_at,finished_at,receipt,error_code FROM execution_action WHERE 1=1";
        var args=new ArrayList<Object>();
        if (workflowId!=null) { sql+=" AND workflow_run_id=?"; args.add(workflowId); }
        if (operationKey!=null) { sql+=" AND idempotency_key=?"; args.add(operationKey); }
        return Result.ok(business.jdbc().queryForList(sql+" ORDER BY id DESC LIMIT 100",args.toArray()));
    }
}
