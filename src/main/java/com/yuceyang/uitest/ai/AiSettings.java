package com.yuceyang.uitest.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** AI 服务配置，只从服务端环境变量读取，密钥不回传浏览器。 */
@Component
public class AiSettings {
    private final String baseUrl;
    private final String model;
    private final String apiKey;

    public AiSettings(@Value("${qts.ai.base-url:}") String baseUrl,
                      @Value("${qts.ai.model:}") String model,
                      @Value("${qts.ai.api-key:}") String apiKey) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.model = model == null ? "" : model.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public String baseUrl() { return baseUrl; }
    public String model() { return model; }
    public boolean hasApiKey() { return !apiKey.isBlank(); }
    /** 仅供服务端调用 AI 接口使用，切勿回传浏览器。 */
    public String apiKey() { return apiKey; }
    public boolean configured() { return !baseUrl.isBlank() && !model.isBlank() && hasApiKey(); }
}
