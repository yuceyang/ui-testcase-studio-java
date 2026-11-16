package com.yuceyang.uitest.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/** OpenAI 兼容的 Chat Completions 调用；密钥只留在服务端。 */
@Component
public class AiClient {
    private final AiSettings settings;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public AiClient(AiSettings settings) {
        this.settings = settings;
    }

    public boolean available() {
        return settings.configured();
    }

    public String model() {
        return settings.model();
    }

    public String chat(String systemPrompt, String userPrompt) {
        if (!settings.configured()) {
            throw new IllegalStateException("AI 未配置：请设置 QTS_AI_BASE_URL / QTS_AI_MODEL / QTS_AI_API_KEY");
        }
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", settings.model());
            body.put("temperature", 0.3);
            body.put("stream", false);
            ArrayNode messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", systemPrompt);
            messages.addObject().put("role", "user").put("content", userPrompt);

            HttpRequest request = HttpRequest.newBuilder(endpoint())
                    // 界面在等这次调用：超时太长会让用户一直卡在“生成中”，太短会让慢模型整批失败。
                    // 实测该网关生成 JSON 需要 30 秒上下，留 100 秒余量。
                    .timeout(Duration.ofSeconds(100))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + settings.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("AI 接口返回 HTTP " + response.statusCode() + "：" + abbreviate(response.body()));
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                throw new IllegalStateException("AI 接口未返回内容：" + abbreviate(response.body()));
            }
            return content.asText();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI 调用被中断", e);
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("AI 调用失败：" + e.getMessage(), e);
        }
    }

    /**
     * 带图调用（OpenAI 兼容的 image_url 数据 URL）。用于截图选区识别。
     * 模型不支持视觉时会返回 4xx/内容为空，由调用方回落到手工命名。
     */
    public String chatVision(String systemPrompt, String userPrompt, byte[] image, String mime) {
        return chatVision(systemPrompt, userPrompt, image == null ? List.of() : List.of(image), mime);
    }

    /**
     * 多图调用：第一张是被识别的框选区域，后面是用户自己补的参考图。
     * 顺序就是提示词里说的「第 1 张 / 后面几张」，这里不再插文字切分（部分网关对图文交错支持不好）。
     */
    public String chatVision(String systemPrompt, String userPrompt, List<byte[]> images, String mime) {
        String text = sendVision(systemPrompt, userPrompt, images, mime);
        if (looksRejected(text)) {
            // 部分 OpenAI 兼容网关会把「system 消息 + 图片」的组合判定为高风险直接拒答
            // （返回 “The request was rejected because it was considered high risk”）。
            // 把系统提示词折叠进同一条 user 文本里再发一次，实测可通过。
            text = sendVision(null, systemPrompt + "\n\n" + userPrompt, images, mime);
            if (looksRejected(text)) throw new IllegalStateException("视觉模型拒答：" + abbreviate(text));
        }
        return text;
    }

    private static boolean looksRejected(String text) {
        if (text == null || text.isBlank()) return true;
        String t = text.trim();
        return !t.startsWith("{") && !t.startsWith("[") && t.length() < 200
                && (t.startsWith("The request was rejected") || t.contains("high risk"));
    }

    private String sendVision(String systemPrompt, String userPrompt, List<byte[]> images, String mime) {
        List<byte[]> pictures = images == null ? List.of() : images;
        if (!settings.configured()) {
            throw new IllegalStateException("AI 未配置：请设置 QTS_AI_BASE_URL / QTS_AI_MODEL / QTS_AI_API_KEY");
        }
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", settings.model());
            body.put("temperature", 0.2);
            body.put("stream", false);
            ArrayNode messages = body.putArray("messages");
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.addObject().put("role", "system").put("content", systemPrompt);
            }
            ObjectNode user = messages.addObject().put("role", "user");
            ArrayNode parts = user.putArray("content");
            parts.addObject().put("type", "text").put("text", userPrompt);
            for (byte[] image : pictures) {
                if (image == null || image.length == 0) continue;
                String dataUrl = "data:" + (mime == null ? "image/png" : mime) + ";base64,"
                        + java.util.Base64.getEncoder().encodeToString(image);
                parts.addObject().put("type", "image_url")
                        .putObject("image_url").put("url", dataUrl);
            }

            HttpRequest request = HttpRequest.newBuilder(endpoint())
                    // 视觉模型要读图，比纯文本慢得多（实测 20~60 秒），给 150 秒。
                    .timeout(Duration.ofSeconds(150))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + settings.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("视觉模型返回 HTTP " + response.statusCode() + "：" + abbreviate(response.body()));
            }
            JsonNode content = mapper.readTree(response.body()).path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                throw new IllegalStateException("视觉模型未返回内容");
            }
            return content.asText();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("视觉模型调用被中断", e);
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("视觉模型调用失败：" + e.getMessage(), e);
        }
    }

    private URI endpoint() {
        String base = settings.baseUrl();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (!base.endsWith("/chat/completions")) base = base + "/chat/completions";
        return URI.create(base);
    }

    private static String abbreviate(String text) {
        if (text == null) return "";
        return text.length() <= 400 ? text : text.substring(0, 400) + "…";
    }
}
