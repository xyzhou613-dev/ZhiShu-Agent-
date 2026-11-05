package com.bemodel.llm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "deepseek")
public class DeepSeekProperties {

    private String baseUrl = "https://api.deepseek.com";
    private String apiKey;
    private String model = "deepseek-v4-flash";
    private int timeoutSeconds = 30;

    /**
     * 输出上限（max_tokens，F1-A）：封顶输出防止异常复读拖满读超时。
     * 按 callType 覆盖见 callMaxTokens；未配置的 callType 回落此全局值。
     */
    private int maxTokens = 4096;

    /**
     * 按 callType 读超时（秒，F1-B）：重提示词调用单独放宽，不牵连其余 13+ 调用类型。
     * 代码内置默认（yml 可覆盖任意键，Spring Boot 对可变 Map 做合并绑定）：
     * MAPPING_SUGGEST 的提示词随「全量已发布概念×属性」增长，试点实测 26.1s 逼近全局 30s。
     */
    private Map<String, Integer> callTimeouts = new HashMap<>(Map.of("MAPPING_SUGGEST", 90));

    /** 按 callType 覆盖输出上限；未配置回落 maxTokens */
    private Map<String, Integer> callMaxTokens = new HashMap<>();

    /** 备路端点（借鉴 4）：api-key 空 = 无备路，chat() 行为与单提供方时代逐字节一致 */
    private Backup backup = new Backup();

    /** 按 callType 指定主路（"primary"|"backup"）；未列出/值非法回落主路。键拼写契约同 callTimeouts */
    private Map<String, String> callPrimaryRoute = new HashMap<>();

    /** 备路三键；base-url 空=沿用主路 URL，model 空=沿用主路模型 */
    @Data
    public static class Backup {
        private String baseUrl = "";
        private String apiKey = "";
        private String model = "";
    }

    /** 主路端点视图（路由层消费；apiKey 空=未启用） */
    public ProviderEndpoint primaryEndpoint() {
        return new ProviderEndpoint("primary", baseUrl, apiKey, model);
    }

    /** 备路端点视图：apiKey 空 = null（无备路）；model/baseUrl 空 = 沿用主路 */
    public ProviderEndpoint backupEndpoint(ProviderEndpoint primary) {
        if (backup == null || !StringUtils.hasText(backup.getApiKey())) {
            return null;
        }
        return new ProviderEndpoint("backup",
                StringUtils.hasText(backup.getBaseUrl()) ? backup.getBaseUrl() : primary.baseUrl(),
                backup.getApiKey(),
                StringUtils.hasText(backup.getModel()) ? backup.getModel() : primary.model());
    }

    public boolean enabled() {
        return StringUtils.hasText(apiKey);
    }
}
