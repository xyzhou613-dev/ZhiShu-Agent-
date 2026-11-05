package com.bemodel.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F1-B 超时余量：按 callType 解析读超时/输出上限（覆盖优先、未配置回落全局），
 * RestClient 按 baseUrl+秒数缓存复用（借鉴 4 改双元键：多 host 不串实例），
 * 备路端点组装契约（无 Key=null、空字段沿用主路）。纯单测：LlmLogService 仅在调用期使用，构造传 null 即可。
 */
class DeepSeekClientTimeoutTest {

    private DeepSeekClient client(DeepSeekProperties props) {
        return new DeepSeekClient(props, new ObjectMapper(), null);
    }

    @Test
    void 解析_内置默认映射生效_未配置回落全局() {
        DeepSeekProperties props = new DeepSeekProperties();
        assertEquals(90, client(props).timeoutFor("MAPPING_SUGGEST"),
                "代码内置默认：重提示词的 MAPPING_SUGGEST 单独放宽");
        assertEquals(30, client(props).timeoutFor("CS_REPLY"), "未配置的 callType 回落全局 30s");
        assertEquals(30, client(props).timeoutFor(null), "null callType 不炸，回落全局");
    }

    @Test
    void 解析_yml覆盖与maxTokens() {
        DeepSeekProperties props = new DeepSeekProperties();
        props.getCallTimeouts().put("CS_REPLY", 120);
        props.getCallMaxTokens().put("QC_REVIEW", 2048);
        DeepSeekClient c = client(props);
        assertEquals(120, c.timeoutFor("CS_REPLY"), "yml 覆盖优先于内置默认");
        assertEquals(90, c.timeoutFor("MAPPING_SUGGEST"), "未覆盖键保留内置默认");
        assertEquals(4096, c.maxTokensFor("CS_REPLY"), "未配置输出上限回落全局");
        assertEquals(2048, c.maxTokensFor("QC_REVIEW"), "按 callType 输出上限生效");
    }

    @Test
    void 缓存_同端点同秒数同实例_异baseUrl不串实例() {
        DeepSeekClient c = client(new DeepSeekProperties());
        ProviderEndpoint a = new ProviderEndpoint("primary", "http://a.example", "k", "m");
        ProviderEndpoint b = new ProviderEndpoint("backup", "http://b.example", "k", "m");
        assertSame(c.restClientFor(a, 30), c.restClientFor(a, 30), "同端点同秒数复用同一 RestClient");
        assertNotSame(c.restClientFor(a, 30), c.restClientFor(a, 90), "不同秒数各建 factory");
        assertNotSame(c.restClientFor(a, 30), c.restClientFor(b, 30),
                "缓存键含 baseUrl：多 host 不串实例（借鉴 4 修复的暗礁）");
    }

    @Test
    void 截断检测_finish_reason_length按失败处理() {
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals("length", DeepSeekClient.finishReason(
                "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"半截\"}}]}", om),
                "截断响应的 finish_reason 提取");
        assertEquals("stop", DeepSeekClient.finishReason("{\"choices\":[{\"finish_reason\":\"stop\"}]}", om));
        assertEquals("", DeepSeekClient.finishReason("不是JSON", om), "解析失败按空串=未截断");
        assertEquals("", DeepSeekClient.finishReason("{\"choices\":[]}", om), "缺 choices 空串不误判");
    }

    @Test
    void 绑定_Binder路径_合并覆盖与键拼写契约() {
        var env = new org.springframework.core.env.StandardEnvironment();
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("t1",
                java.util.Map.of("deepseek.call-timeouts.CS_REPLY", "120")));
        DeepSeekProperties bound = new DeepSeekProperties();
        org.springframework.boot.context.properties.bind.Binder.get(env)
                .bind("deepseek", org.springframework.boot.context.properties.bind.Bindable.ofInstance(bound));
        assertEquals(120, bound.getCallTimeouts().get("CS_REPLY"), "yml 部分覆盖生效（真实 Binder 路径）");
        assertEquals(90, bound.getCallTimeouts().get("MAPPING_SUGGEST"), "部分覆盖不冲掉内置默认（合并绑定契约）");

        // 键拼写契约：横线变体被 relaxed binding 静默绑成另一个键（不报错），原样键不生效——
        // 固化此行为，让「键拼错静默回落 30s」从无感变成显式契约
        var env2 = new org.springframework.core.env.StandardEnvironment();
        env2.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("t2",
                java.util.Map.of("deepseek.call-timeouts.MAPPING-SUGGEST", "120")));
        DeepSeekProperties bound2 = new DeepSeekProperties();
        org.springframework.boot.context.properties.bind.Binder.get(env2)
                .bind("deepseek", org.springframework.boot.context.properties.bind.Bindable.ofInstance(bound2));
        assertEquals(90, bound2.getCallTimeouts().get("MAPPING_SUGGEST"), "拼错键不得覆盖内置默认");
        assertTrue(bound2.getCallTimeouts().containsKey("MAPPING-SUGGEST"),
                "横线键按原样绑入 Map（relaxed binding 不报错的显式固化）");

        // 借鉴 4：backup.* 嵌套键与 call-primary-route Map 同走合并绑定
        var env3 = new org.springframework.core.env.StandardEnvironment();
        env3.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("t3",
                java.util.Map.of("deepseek.backup.api-key", "k2",
                        "deepseek.backup.base-url", "http://backup.example",
                        "deepseek.call-primary-route.LAB_A", "backup")));
        DeepSeekProperties bound3 = new DeepSeekProperties();
        org.springframework.boot.context.properties.bind.Binder.get(env3)
                .bind("deepseek", org.springframework.boot.context.properties.bind.Bindable.ofInstance(bound3));
        assertEquals("k2", bound3.getBackup().getApiKey());
        assertEquals("http://backup.example", bound3.getBackup().getBaseUrl());
        assertEquals("backup", bound3.getCallPrimaryRoute().get("LAB_A"), "按 callType 指定主路的 Map 合并绑定生效");
    }

    @Test
    void 端点组装_备路无Key为null_空字段沿用主路() {
        DeepSeekProperties props = new DeepSeekProperties();
        props.setApiKey("k-main");
        ProviderEndpoint primary = props.primaryEndpoint();
        assertEquals("primary", primary.name());
        assertNull(props.backupEndpoint(primary), "备路 api-key 空 = 无备路（行为与单提供方时代一致）");

        props.getBackup().setApiKey("k-backup");
        ProviderEndpoint backup = props.backupEndpoint(primary);
        assertEquals("backup", backup.name());
        assertEquals("deepseek-v4-flash", backup.model(), "备路 model 空 = 沿用主路模型");
        assertEquals("https://api.deepseek.com", backup.baseUrl(), "备路 base-url 空 = 沿用主路 URL");

        props.getBackup().setModel("deepseek-v4-flash");
        props.getBackup().setBaseUrl("http://backup.example");
        assertEquals("http://backup.example", props.backupEndpoint(primary).baseUrl());
    }
}
