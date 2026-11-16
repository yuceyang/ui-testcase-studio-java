package com.yuceyang.uitest.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuceyang.uitest.element.ElementCandidate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 发现元素后的 AI 预筛：判断哪些元素值得作为功能点测试、哪些是噪声
 * （分页 1/2/3、上一页/下一页、重复的时间选择框等），并把同一组控件归并成一个功能点。
 * AI 不可用或调用失败时退回规则判断，界面行为保持一致。
 */
@Service
public class ElementAdvisor {
    /**
     * suggested=建议作为测试点；group=归并组名（同组元素只生成一个功能点）；reason=判断理由；
     * correctedName=AI 修正后的元素名称（不需要修正时为空）；drop=识别错误、不是用户真实意图，应从列表里删除。
     */
    public record Hint(boolean suggested, String group, String reason, String correctedName, boolean drop) {
        public Hint(boolean suggested, String group, String reason) {
            this(suggested, group, reason, null, false);
        }
    }

    private static final String SYSTEM_PROMPT = """
            你是资深软件测试分析师，负责整理网页自动化发现的可交互元素。
            目标：挑出值得测试的功能点，剔除噪声与误识别，并把同一类控件归并成一个功能点。

            判定规则：
            1. 分页控件（单独页码、上一页、下一页、首页、末页、跳转页码）→ verdict=skip，group=分页。
            2. 重复的同类控件（多组「开始时间/结束时间」）→ 只保留一个代表，其余 skip 或同 group 归并。
            3. 查询、重置、搜索、导出按钮 → verdict=test，通常 group=查询条件。
            4. 表单输入框（项目名称、用户名、关键字、日期）→ verdict=test，按业务含义 group。
            5. 纯图标链接、空白链接、装饰性元素 → verdict=skip。
            6. 名称里明显是多个字段的标签/占位提示拼接在一起（例如「项目名称 * 请输入项目名称 所属用户 * …」），
               或明显是布局容器（弹窗外壳、表单分组、滚动容器、卡片包装）而不是一个可操作控件 → verdict=drop。
            7. name 字段：当 name 明显不是控件本身的名称（拼接文本、空、只有 * 号、只有图标）时，用简短中文给出
               正确的名称（如「创建项目弹窗」「项目名称输入框」「确定按钮」）；名称本来就准确时留空字符串。
            8. 不要把同一控件的不同实例当成多个功能点。

            只输出 JSON，不要 Markdown：
            {"elements":[{"id":"元素ID","verdict":"test|skip|drop","name":"修正后的名称或空",
              "group":"归并组名，没有则为空","reason":"一句话理由"}]}
            """;

    private static final Logger log = LoggerFactory.getLogger(ElementAdvisor.class);
    private static final int MAX_ITEMS = 160;

    private final AiClient aiClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public ElementAdvisor(AiClient aiClient) {
        this.aiClient = aiClient;
    }

    public Map<String, Hint> advise(List<ElementCandidate> elements) {
        if (elements == null || elements.isEmpty()) return Map.of();
        List<ElementCandidate> items = elements.size() > MAX_ITEMS ? elements.subList(0, MAX_ITEMS) : elements;
        if (aiClient.available()) {
            try {
                Map<String, Hint> hints = fromAi(items);
                if (!hints.isEmpty()) {
                    // AI 只回了一部分时，剩余元素按规则补齐，避免界面上大片“无建议”。
                    Map<String, Hint> merged = new LinkedHashMap<>(heuristics(items));
                    merged.putAll(hints);
                    return merged;
                }
            } catch (RuntimeException e) {
                log.warn("AI 元素预筛失败，改用规则判断：{}", e.getMessage());
            }
        }
        return heuristics(elements);
    }

    private Map<String, Hint> fromAi(List<ElementCandidate> items) {
        List<Map<String, Object>> compact = new ArrayList<>();
        for (ElementCandidate item : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.getId());
            row.put("name", trim(item.getName(), 40));
            row.put("kind", item.getKind());
            row.put("selector", trim(item.getSelector(), 120));
            row.put("path", trim(item.getSemanticPath(), 120));
            compact.add(row);
        }
        String prompt;
        try {
            prompt = "请判断以下元素，输出 JSON：\n"
                    + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(compact);
        } catch (Exception e) {
            throw new IllegalStateException("构造元素预筛请求失败：" + e.getMessage(), e);
        }
        String text = aiClient.chat(SYSTEM_PROMPT, prompt);
        JsonNode root;
        try {
            root = mapper.readTree(stripFences(text));
        } catch (Exception e) {
            throw new IllegalStateException("AI 预筛结果不是合法 JSON：" + e.getMessage(), e);
        }
        Map<String, Hint> hints = new LinkedHashMap<>();
        for (JsonNode node : root.path("elements")) {
            String id = node.path("id").asText("");
            if (id.isBlank()) continue;
            String verdict = node.path("verdict").asText("test").trim().toLowerCase(Locale.ROOT);
            boolean suggested = "test".equals(verdict);
            boolean drop = "drop".equals(verdict);
            String group = trim(node.path("group").asText(""), 20);
            String reason = trim(node.path("reason").asText(""), 60);
            String corrected = trim(node.path("name").asText(""), 40);
            hints.put(id, new Hint(suggested, group.isBlank() ? null : group, reason,
                    corrected.isBlank() ? null : corrected, drop));
        }
        return hints;
    }

    /** 规则兜底：页码与翻页按钮跳过，同名控件归组，其余默认建议测试。 */
    public Map<String, Hint> heuristics(List<ElementCandidate> elements) {
        Map<String, Integer> nameCount = new LinkedHashMap<>();
        for (ElementCandidate element : elements) {
            nameCount.merge(label(element), 1, Integer::sum);
        }
        Map<String, Hint> hints = new LinkedHashMap<>();
        for (ElementCandidate element : elements) {
            String name = label(element);
            String lower = name.toLowerCase(Locale.ROOT);
            if (name.matches("\\d{1,3}") || name.matches("[<«‹>»›\\[\\]]+")) {
                hints.put(element.getId(), new Hint(false, "分页", "页码/翻页控件，合并到分页测试"));
                continue;
            }
            if (lower.contains("下一页") || lower.contains("上一页") || lower.contains("首页")
                    || lower.contains("末页") || lower.contains("尾页")
                    || lower.equals("next") || lower.equals("prev") || lower.equals("previous")) {
                hints.put(element.getId(), new Hint(false, "分页", "翻页按钮，合并到分页测试"));
                continue;
            }
            if (name.isBlank()) {
                hints.put(element.getId(), new Hint(false, null, "无名称/纯图标元素，无法确定测试点"));
                continue;
            }
            // 名称是多段标签/占位提示拼起来的，多半是误识别的容器：规则兜底时直接判为删除。
            if (name.length() > 24 && (occurrences(name, "请输入") >= 2 || name.split("\\*").length >= 3)) {
                hints.put(element.getId(), new Hint(false, null, "名称是多段文本拼接的容器/误识别元素", null, true));
                continue;
            }
            String group = null;
            String reason = "可直接作为功能点";
            if (name.matches(".*(开始时间|结束时间|开始日期|结束日期).*")) {
                group = "时间范围筛选";
                reason = "时间范围类控件，合并为一个功能点";
            } else if (nameCount.getOrDefault(name, 0) > 1) {
                group = name;
                reason = "同名控件出现多次，合并为一个功能点";
            } else if (name.matches(".*(查询|搜索|检索).*")) {
                group = "查询条件";
                reason = "查询入口，合并到查询条件";
            }
            hints.put(element.getId(), new Hint(true, group, reason));
        }
        return hints;
    }

    private static String label(ElementCandidate element) {
        String name = element.getName() == null ? "" : element.getName().trim();
        if (name.isEmpty() && element.getKind() != null) {
            name = element.getKind();
        }
        return name;
    }

    private static int occurrences(String text, String token) {
        int count = 0, index = 0;
        while ((index = text.indexOf(token, index)) >= 0) { count++; index += token.length(); }
        return count;
    }

    private static String trim(String value, int max) {
        if (value == null) return "";
        String v = value.replaceAll("\\s+", " ").trim();
        return v.length() <= max ? v : v.substring(0, max);
    }

    private static String stripFences(String text) {
        String t = text == null ? "" : text.trim();
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start >= 0 && end > start) t = t.substring(start, end + 1);
        return t;
    }
}
