package com.bemodel.compute;

import com.bemodel.auth.CurrentUser;
import com.bemodel.common.BizException;
import com.bemodel.modeling.entity.Release;
import com.bemodel.modeling.entity.Rule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Statement;
import java.util.*;
import java.util.function.Supplier;

/** Durable platform staging followed by idempotent business-DB activation; no distributed transaction claim. */
@Service
@Profile("compute")
public class ComputeReleaseBridge {
    private final JdbcTemplate platform;
    private final ComputeDatabase.Store store;
    private final ComputeRuleService rules;
    private final ObjectMapper json;
    private final PlatformTransactionManager platformTx;
    private final long ownerId;
    private final String nodeKey;
    public ComputeReleaseBridge(JdbcTemplate platform, ComputeDatabase.Store store, ComputeRuleService rules,
            ObjectMapper json, @Qualifier("transactionManager") PlatformTransactionManager platformTx,
            @Value("${compute.rules.owner-employee-id:1}") long ownerId,
            @Value("${compute.rules.node-key:a800-demo}") String nodeKey) {
        this.platform=platform; this.store=store; this.rules=rules; this.json=json;
        this.platformTx=platformTx; this.ownerId=ownerId; this.nodeKey=nodeKey;
    }
    public void lockPublication() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Publication requires platform transaction");
        platform.queryForObject("SELECT id FROM bm_compute_publish_lock WHERE id=1 FOR UPDATE",Integer.class);
    }
    public Map<String,Object> prepare(List<Rule> published) {
        var checked=validateRules(published);
        if (platform.queryForObject("SELECT COUNT(*) FROM bm_compute_rule_conflict WHERE status='OPEN'",Integer.class)>0)
            throw new BizException("存在未解决的算力规则冲突，禁止发布");
        var j=store.jdbc();
        if (ownerId<=0 || j.queryForObject("SELECT COUNT(*) FROM employee WHERE id=?",Integer.class,ownerId)!=1)
            throw new BizException("请配置有效的 compute.rules.owner-employee-id（员工编号）");
        var groups=j.queryForList("SELECT g.id,g.group_code,COUNT(d.id) AS cards FROM gpu_group g JOIN compute_node n ON n.id=g.node_id LEFT JOIN gpu_device d ON d.group_id=g.id WHERE n.node_key=? GROUP BY g.id,g.group_code ORDER BY g.id",nodeKey);
        Map<String,Integer> required=Map.of("A",4,"B",2,"C",1,"FLEX",1);
        if(groups.size()!=4 || groups.stream().anyMatch(g->!required.containsKey(g.get("group_code")) || ((Number)g.get("cards")).intValue()!=required.get(g.get("group_code"))))
            throw new BizException("A/B/C/FLEX 配置必须对应同节点的 4/2/1/1 张卡");
        var training=j.queryForList("SELECT priority FROM task_type WHERE type_code='TRAINING'");
        if (training.size()!=1 || ((Number)training.getFirst().get("priority")).intValue()<=1) throw new BizException("训练等级必须高于常驻部署等级 1");
        var config=new LinkedHashMap<String,Object>();
        config.put("evaluatorVersion",ComputeRuleEvaluator.VERSION);
        config.put("formulaVersion",ComputeVersions.FORMULAS);
        config.put("ownerEmployeeId",ownerId); config.put("nodeKey",nodeKey); config.put("groups",groups);
        config.put("trainingPriority",training.getFirst().get("priority")); config.put("residentPriority",1);
        config.put("dataAssets",platform.queryForList("SELECT asset_code,version,owner,source_kind,authoritative_source,max_age_seconds,sensitivity FROM bm_compute_data_asset ORDER BY asset_code"));
        Long active=j.queryForObject("SELECT rule_set_id FROM active_rule_set WHERE id=1",Long.class);
        config.put("expectedActiveSetId",active==null?0L:active);
        config.put("rules",checked);
        return config;
    }
    private List<Rule> validateRules(List<Rule> source) {
        var checked=source.stream().filter(r->"COMPUTE".equals(r.getEngine()) || r.getConceptCode()!=null && r.getConceptCode().startsWith("COMPUTE_")).sorted(Comparator.comparing(Rule::getRuleCode)).toList();
        if (checked.size()!=14 || !new HashSet<>(checked.stream().map(Rule::getRuleCode).toList()).equals(new HashSet<>(ComputeRuleEvaluator.CODES)))
            throw new BizException("发布要求完整的 14 条算力规则（不含 R-09）");
        for(var r:checked) { if(!"PUBLISHED".equals(r.getStatus())) throw new BizException("规则尚未发布: "+r.getRuleCode()); rules.validate(r); }
        return checked;
    }
    public void stage(Release release) {
        platform.update("INSERT INTO bm_compute_release_sync(release_id,payload_hash) VALUES(?,?)",release.getId(),hash(release.getSnapshotJson()));
        audit("RELEASE_STAGED",release.getId().toString(),Map.of("versionTag",release.getVersionTag()));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { sync(release.getId()); }
        });
    }
    public Map<String,Object> sync(long releaseId) {
        String actor=Objects.requireNonNullElse(CurrentUser.username(),"release-sync");
        try {
            var source=platformTransaction(()->{
                var rows=platform.queryForList("SELECT r.id,r.version_tag,r.snapshot_json,s.payload_hash FROM bm_release r JOIN bm_compute_release_sync s ON s.release_id=r.id WHERE r.id=? FOR UPDATE",releaseId);
                if(rows.size()!=1) throw new BizException("该发布没有算力同步记录");
                platform.update("UPDATE bm_compute_release_sync SET attempt_count=attempt_count+1,updated_at=UTC_TIMESTAMP(6) WHERE release_id=?",releaseId);
                return rows.getFirst();
            });
            String payload=source.get("snapshot_json").toString();
            if(!hash(payload).equals(source.get("payload_hash"))) throw new BizException("发布快照与登记摘要不一致");
            JsonNode cfg=json.readTree(payload).path("compute");
            if(!ComputeVersions.FORMULAS.equals(cfg.path("formulaVersion").asText()) || !ComputeRuleEvaluator.VERSION.equals(cfg.path("evaluatorVersion").asText()))
                throw new BizException("当前程序不支持该发布的规则或公式实现版本");
            var snapshots=new ArrayList<Rule>();
            for(JsonNode r:cfg.path("rules")) snapshots.add(json.treeToValue(r,Rule.class));
            validateRules(snapshots);
            var businessTx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(store.jdbc().getDataSource())));
            Long setId=businessTx.execute(status->activate(releaseId,source.get("version_tag").toString(),source.get("payload_hash").toString(),cfg,snapshots,actor));
            platformTransaction(()->{
                platform.update("UPDATE bm_compute_release_sync SET status='SYNCED',active_set_id=?,last_error=NULL,updated_at=UTC_TIMESTAMP(6) WHERE release_id=?",setId,releaseId);
                audit("RELEASE_SYNCED",Long.toString(releaseId),Map.of("ruleSetId",setId)); return null;
            });
        } catch(Exception failure) {
            String message=failure instanceof BizException?failure.getMessage():"规则同步未完成确认："+failure.getClass().getSimpleName()+"；请核对生效集合后重试原快照";
            platformTransaction(()->{ platform.update("UPDATE bm_compute_release_sync SET status='FAILED',last_error=?,updated_at=UTC_TIMESTAMP(6) WHERE release_id=?",message.substring(0,Math.min(512,message.length())),releaseId); audit("RELEASE_SYNC_FAILED",Long.toString(releaseId),Map.of("reason",message)); return null; });
        }
        return platformTransaction(()->platform.queryForMap("SELECT * FROM bm_compute_release_sync WHERE release_id=?",releaseId));
    }
    private long activate(long releaseId,String tag,String digest,JsonNode cfg,List<Rule> snapshots,String actor) {
        var j=store.jdbc();
        Long active=j.queryForObject("SELECT rule_set_id FROM active_rule_set WHERE id=1 FOR UPDATE",Long.class);
        var existing=j.queryForList("SELECT id,payload_hash FROM rule_set WHERE source_release_id=?",releaseId);
        if(!existing.isEmpty()) {
            if(!digest.equals(existing.getFirst().get("payload_hash"))) throw new BizException("发布编号已对应不同快照");
            return ((Number)existing.getFirst().get("id")).longValue();
        }
        if((active==null?0L:active)!=cfg.path("expectedActiveSetId").asLong(-1)) throw new BizException("生效集合已被其他发布切换，请重新发布；不能用旧快照覆盖新集合");
        if(j.queryForObject("SELECT COUNT(*) FROM employee WHERE id=?",Integer.class,cfg.path("ownerEmployeeId").asLong())!=1) throw new BizException("规则负责人不存在");
        long setId=insert(j,"INSERT INTO rule_set(source_release_id,source_version_tag,payload_hash,evaluator_version,formula_version,settings) VALUES(?,?,?,?,?,?)",releaseId,tag,digest,ComputeRuleEvaluator.VERSION,ComputeVersions.FORMULAS,cfg.toString());
        for(var rule:snapshots) {
            int version=j.queryForObject("SELECT COALESCE(MAX(version),0)+1 FROM rule_version WHERE rule_code=?",Integer.class,rule.getRuleCode());
            JsonNode def=rules.validate(rule);
            long versionId=insert(j,"INSERT INTO rule_version(rule_code,version,source_rule_id,source_release_id,evaluator_version,stage,rule_type,owner_employee_id,effective_from,definition,reason_codes) VALUES(?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6),?,?)",rule.getRuleCode(),version,rule.getId(),releaseId,ComputeRuleEvaluator.VERSION,def.path("stage").asText(),def.path("kind").asText(),cfg.path("ownerEmployeeId").asLong(),rule.getExprJson(),def.path("failureReasons").toString());
            j.update("INSERT INTO rule_set_member VALUES(?,?,?)",setId,rule.getRuleCode(),versionId);
        }
        if(j.queryForObject("SELECT COUNT(*) FROM rule_set_member WHERE rule_set_id=?",Integer.class,setId)!=14) throw new BizException("规则集合不完整，禁止激活");
        j.update("INSERT INTO rule_activation(rule_set_id,previous_set_id,actor) VALUES(?,?,?)",setId,active,actor);
        j.update("UPDATE active_rule_set SET rule_set_id=? WHERE id=1",setId);
        return setId;
    }
    public List<Map<String,Object>> history() {
        return platform.queryForList("SELECT s.*,r.version_tag FROM bm_compute_release_sync s JOIN bm_release r ON r.id=s.release_id ORDER BY release_id DESC LIMIT 100");
    }
    public Map<String,Object> active() {
        var rows=store.jdbc().queryForList("SELECT s.id,s.source_release_id,s.source_version_tag,s.evaluator_version,s.formula_version,a.actor,a.activated_at FROM active_rule_set p JOIN rule_set s ON s.id=p.rule_set_id JOIN rule_activation a ON a.rule_set_id=s.id WHERE p.id=1");
        return rows.isEmpty()?Map.of("active",false):rows.getFirst();
    }
    /** Pin this set ID in the decision; never ask for "latest" separately for each rule. */
    public Map<String,Object> snapshot(long setId) {
        var j=store.jdbc();
        var sets=j.queryForList("SELECT * FROM rule_set WHERE id=?",setId);
        if(sets.size()!=1) throw new BizException("运行规则集合不存在");
        var versions=j.queryForList("SELECT v.* FROM rule_set_member m JOIN rule_version v ON v.id=m.rule_version_id WHERE m.rule_set_id=? ORDER BY m.rule_code",setId);
        if(versions.size()!=14) throw new BizException("运行规则集合不完整");
        return Map.of("set",sets.getFirst(),"rules",versions);
    }
    public void audit(String operation,String ref,Map<String,?> detail) {
        try { platform.update("INSERT INTO bm_compute_operation_audit(actor,operation,subject_ref,detail) VALUES(?,?,?,?)",Objects.requireNonNullElse(CurrentUser.username(),"release-sync"),operation,ref,json.writeValueAsString(detail)); }
        catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalArgumentException(e); }
    }
    private <T> T platformTransaction(Supplier<T> work) {
        var tx=new TransactionTemplate(platformTx); tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(status->work.get());
    }
    static long insert(JdbcTemplate jdbc,String sql,Object...args) {
        var key=new GeneratedKeyHolder();
        jdbc.update(c->{ var s=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS); for(int i=0;i<args.length;i++) s.setObject(i+1,args[i]); return s; },key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
