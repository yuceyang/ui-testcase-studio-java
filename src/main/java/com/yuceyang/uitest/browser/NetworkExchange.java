package com.yuceyang.uitest.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 浏览器会话里真实捕获到的一次 HTTP 往返。
 * 只留方法、URL、资源类型、状态码与请求体；不存 Cookie / Authorization 等头，
 * 请求体里 password/token 一类的值在入库前就被替换成 {@code ***}。
 * 目的是给用例的「携带参数 / 测试数据」提供真实字段与真实值，而不是让模型按界面文案编。
 */
public record NetworkExchange(String sessionId, String method, String url, String resourceType,
                              int status, String body, long atEpochMs) {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> STATIC_EXT = List.of(".css", ".js", ".png", ".jpg", ".jpeg", ".gif",
            ".svg", ".woff", ".woff2", ".ttf", ".ico", ".map", ".webp");
    private static final List<String> SECRET_KEYS = List.of("password", "passwd", "pwd", "secret",
            "token", "authorization", "accesstoken", "refreshtoken", "sessionid");
    /** 请求体只留这么多字符：够看清参数名与值，又不至于让提示词爆掉。 */
    private static final int MAX_BODY = 600;

    /** 只保留可能带业务参数的请求：xhr/fetch，或带请求体的文档请求。 */
    public boolean isApiCall() {
        if ("xhr".equals(resourceType) || "fetch".equals(resourceType)) return true;
        return "document".equals(resourceType) && body != null && !body.isBlank()
                && !"get".equalsIgnoreCase(method);
    }

    public boolean isStatic() {
        String path = url == null ? "" : url.toLowerCase(Locale.ROOT);
        for (String ext : STATIC_EXT) if (path.contains(ext)) return true;
        return false;
    }

    /** 接口签名：同一接口的重复请求（轮询、翻页）只算一次。 */
    public String key() {
        return method + " " + url + " " + (body == null ? "" : body);
    }

    /** 一行标题 + 逐行参数，供「携带参数 / 测试数据」直接引用。 */
    public String describe() {
        StringBuilder sb = new StringBuilder(method).append(' ').append(url);
        if (status > 0) sb.append(" → ").append(status);
        String params = params();
        if (!params.isBlank()) sb.append('\n').append(params);
        return sb.toString();
    }

    /** URL 查询串 + 请求体，拆成逐行「参数名=值」。 */
    public String params() {
        List<String> lines = new ArrayList<>();
        appendQuery(lines, url);
        appendBody(lines, body);
        return String.join("\n", lines);
    }

    private static void appendQuery(List<String> lines, String url) {
        if (url == null) return;
        try {
            String query = URI.create(url).getRawQuery();
            if (query != null && !query.isBlank()) appendPairs(lines, query);
        } catch (IllegalArgumentException ignored) {
            int q = url.indexOf('?');
            if (q >= 0) appendPairs(lines, url.substring(q + 1));
        }
    }

    private static void appendPairs(List<String> lines, String raw) {
        for (String pair : raw.split("&")) {
            if (pair.isBlank()) continue;
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            if (name.isBlank()) continue;
            lines.add(decode(name) + "=" + (isSecret(name) ? "***" : decode(value)));
        }
    }

    private static void appendBody(List<String> lines, String body) {
        if (body == null || body.isBlank()) return;
        String trimmed = body.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                appendJson(lines, MAPPER.readTree(trimmed), "");
                return;
            } catch (Exception ignored) {
                // 不是合法 JSON 就按表单处理
            }
        }
        appendPairs(lines, trimmed);
    }

    private static void appendJson(List<String> lines, JsonNode node, String prefix) {
        if (node == null) return;
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String name = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
                JsonNode value = entry.getValue();
                if (value.isObject() || value.isArray()) {
                    appendJson(lines, value, name);
                } else {
                    lines.add(name + "=" + (isSecret(name) ? "***" : value.asText("")));
                }
            }
            return;
        }
        if (node.isArray()) {
            int index = 0;
            for (JsonNode item : node) {
                String name = prefix + "[" + index++ + "]";
                if (item.isObject() || item.isArray()) appendJson(lines, item, name);
                else lines.add(name + "=" + item.asText(""));
            }
        }
    }

    private static boolean isSecret(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        for (String secret : SECRET_KEYS) if (lower.contains(secret)) return true;
        return false;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return value;
        }
    }

    /** 请求体先做长度裁剪，再按字段名抹掉凭据一类的值（保持原格式，params() 还能继续拆）。 */
    public static String redact(String body) {
        if (body == null) return null;
        String text = body.length() > MAX_BODY ? body.substring(0, MAX_BODY) : body;
        String trimmed = text.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return MAPPER.writeValueAsString(mask(MAPPER.readTree(trimmed)));
            } catch (Exception ignored) {
                return text;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String pair : text.split("&")) {
            if (!sb.isEmpty()) sb.append('&');
            int eq = pair.indexOf('=');
            if (eq > 0 && isSecret(pair.substring(0, eq))) {
                sb.append(pair, 0, eq + 1).append("***");
            } else {
                sb.append(pair);
            }
        }
        return sb.toString();
    }

    private static JsonNode mask(JsonNode node) {
        if (node == null) return null;
        if (node.isObject()) {
            com.fasterxml.jackson.databind.node.ObjectNode copy = MAPPER.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode value = entry.getValue();
                if (value != null && (value.isObject() || value.isArray())) copy.set(entry.getKey(), mask(value));
                else if (isSecret(entry.getKey())) copy.put(entry.getKey(), "***");
                else copy.set(entry.getKey(), value);
            }
            return copy;
        }
        if (node.isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode copy = MAPPER.createArrayNode();
            for (JsonNode item : node) {
                if (item.isObject() || item.isArray()) copy.add(mask(item));
                else copy.add(item);
            }
            return copy;
        }
        return node;
    }
}
