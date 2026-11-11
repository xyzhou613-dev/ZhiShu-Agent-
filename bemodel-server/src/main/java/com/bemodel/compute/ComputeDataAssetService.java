package com.bemodel.compute;

import com.bemodel.auth.CurrentUser;
import com.bemodel.common.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@Profile("compute")
public class ComputeDataAssetService {
    public record Asset(String assetCode,String name,String owner,String sourceKind,String authoritativeSource,
            String updateRequirement,Integer maxAgeSeconds,String sensitivity,String qualityRequirement,
            JsonNode fields,int version,String updatedBy,boolean knowledgeAllowed) {}
    private final JdbcTemplate platform;
    private final ObjectMapper json;
    public ComputeDataAssetService(JdbcTemplate platform,ObjectMapper json) { this.platform=platform;this.json=json; }
    public List<Asset> list(boolean forAgent) {
        var assets=platform.query("SELECT * FROM bm_compute_data_asset ORDER BY asset_code",(rs,n)->new Asset(rs.getString("asset_code"),rs.getString("name"),rs.getString("owner"),rs.getString("source_kind"),rs.getString("authoritative_source"),rs.getString("update_requirement"),rs.getObject("max_age_seconds",Integer.class),rs.getString("sensitivity"),rs.getString("quality_requirement"),read(rs.getString("fields_json")),rs.getInt("version"),rs.getString("updated_by"),!Set.of("IDENTITY_ORGANIZATION","ACCOUNT_OPERATION_LOG","RESOURCE_USAGE_LOG").contains(rs.getString("asset_code"))));
        if(!forAgent) return assets;
        return assets.stream().filter(a->!"SENSITIVE".equals(a.sensitivity())).map(a->{
            var fields=json.createArrayNode();
            a.fields().forEach(f->{if(!"SENSITIVE".equals(f.path("sensitivity").asText())) fields.add(f);});
            return new Asset(a.assetCode(),a.name(),a.owner(),a.sourceKind(),a.authoritativeSource(),a.updateRequirement(),a.maxAgeSeconds(),a.sensitivity(),a.qualityRequirement(),fields,a.version(),a.updatedBy(),a.knowledgeAllowed());
        }).toList();
    }
    public Asset require(String code) { return list(false).stream().filter(a->a.assetCode().equals(code)).findFirst().orElseThrow(()->new BizException("数据资产不存在")); }
    @Transactional
    public Asset update(String code,JsonNode body) {
        Asset old=require(code);
        if(!body.path("version").isIntegralNumber() || body.path("version").asLong()!=old.version()) throw new BizException("登记已被修改，请刷新版本");
        String owner=text(body,"owner",128),source=text(body,"authoritativeSource",128),kind=text(body,"sourceKind",24),update=text(body,"updateRequirement",512),sensitivity=text(body,"sensitivity",16),quality=text(body,"qualityRequirement",512);
        if(!Set.of("SIMULATED","PLATFORM","EXTERNAL_PENDING","CONNECTED").contains(kind) || !Set.of("PUBLIC","INTERNAL","SENSITIVE").contains(sensitivity)) throw new BizException("数据来源类型或敏感等级非法");
        JsonNode max=body.path("maxAgeSeconds");
        Integer age=max.isMissingNode()||max.isNull()?null: max.isIntegralNumber()&&max.canConvertToInt()&&max.intValue()>0?max.intValue():-1;
        if(age!=null && age<1) throw new BizException("最大年龄须是正整数秒；采集频率不能直接当有效期");
        String actor=Objects.requireNonNullElse(CurrentUser.username(),"system");
        if(platform.update("UPDATE bm_compute_data_asset SET owner=?,source_kind=?,authoritative_source=?,update_requirement=?,max_age_seconds=?,sensitivity=?,quality_requirement=?,version=version+1,updated_by=?,updated_at=UTC_TIMESTAMP(6) WHERE asset_code=? AND version=?",owner,kind,source,update,age,sensitivity,quality,actor,code,old.version())!=1) throw new BizException("登记已被其他人修改，请刷新");
        var changed=require(code);
        try { platform.update("INSERT INTO bm_compute_operation_audit(actor,operation,subject_ref,detail) VALUES(?,'DATA_ASSET_UPDATED',?,?)",actor,code,json.writeValueAsString(Map.of("previous",old,"current",changed))); }
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
        return changed;
    }
    /** Positive allowlist for future Agent tools; never serialize a raw source row wholesale. */
    public Map<String,Object> projectForAgent(String code,String table,Map<String,Object> raw) {
        var asset=require(code);var safe=new LinkedHashMap<String,Object>();
        if("SENSITIVE".equals(asset.sensitivity()) || "EXTERNAL_PENDING".equals(asset.sourceKind())) return safe;
        for(var field:asset.fields()) {
            String column=field.path("column").asText();
            if(table.equals(field.path("table").asText()) && !"SENSITIVE".equals(field.path("sensitivity").asText()) && raw.containsKey(column)) {
                Object value=raw.get(column);
                if(value==null || value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof java.time.temporal.TemporalAccessor) safe.put(column,value);
            }
        }
        return safe;
    }
    public Map<String,Object> collectionContract() {
        return Map.of("requiredFields",List.of("subjectId","resourceId","eventType","timestamp","value","source"),"timeFormat","UTC ISO-8601","gpuIdentity",List.of("gpuUuid","nodeId","localIndex"),"failurePolicy","采集失败显式报 ERROR，不能补零、改旧采集时间或充当健康空闲","authorityPolicy","以登记的权威来源为准；监控事实与平台预留分别保存；安全冲突先阻断核对","ingestionEnabled",false,"assets",list(true));
    }
    private JsonNode read(String value) { try{return json.readTree(value);}catch(Exception e){throw new IllegalStateException("数据资产字段契约损坏",e);} }
    private String text(JsonNode b,String k,int max) { var v=b.path(k);if(!v.isTextual()||v.asText().isBlank()||v.asText().length()>max) throw new BizException(k+" 必须是非空文本且长度不超过 "+max);return v.asText().trim(); }
}
