package com.yuceyang.uitest.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuceyang.uitest.element.ElementCandidate;
import com.yuceyang.uitest.tree.NodeType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 自动建树的提案生成：把「页面 + 已发现元素」变成一棵 模块/一二级三级标题/功能点 的树。
 * 已配置模型时交给 AI 归纳菜单与控件；未配置或调用失败时按语义路径与组件分组兜底。
 * 生成结果只用于界面预览，用户可以在弹框里改名、改类型、拖到别的节点下再入库。
 */
@Service
public class TreeAdvisor {
    /** 一条树节点提案；parentId 为 null 表示顶层，elementId 表示该节点由哪个元素生成。 */
    public record Proposition(String id, String name, NodeType type, String parentId, String elementId) {}

    private static final Logger log = LoggerFactory.getLogger(TreeAdvisor.class);
    private static final int MAX_ELEMENTS = 120;
    private static final int MAX_NODES = 160;

    private static final String SYSTEM_PROMPT = """
            你是资深测试架构师，要把一个待测网页整理成用于功能测试的功能树。
            输入：被测系统名、当前页面地址、页面上发现的可交互元素（名称/类型/组件/语义路径）。

            输出要求：
            1. 结构层级固定为：模块 → 一级标题 → 二级标题 → 三级标题 → 功能点，可以少用层级，但不能乱序。
            2. 顶层至少一个「模块」；系统名适合做模块名。
            3. 导航/菜单项优先做「一级标题」；页面内的卡片、表格、弹窗区域做「二级标题」或「三级标题」。
            4. 真正可操作的控件（查询、新增、编辑、导出、输入框、下拉框…）做「功能点」，名称沿用页面中文文案。
            5. 分页（1/2/3、上一页、下一页、首页、末页）、纯图标、空白按钮等噪声不要建节点。
            6. 同一类控件只留一个功能点（例如多组「开始时间/结束时间」合并成一个「时间范围筛选」）。
            7. 不要自造页面上不存在的业务名词；名称尽量不超过 12 个字。

            只输出 JSON，不要 Markdown：
            {"nodes":[{"id":"n1","name":"资源中心","type":"一级标题","parent":"n0","element":"元素ID或空"}]}
            type 只能是：模块 / 一级标题 / 二级标题 / 三级标题 / 功能点；parent 为空表示顶层。
            """;

    private final AiClient aiClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public TreeAdvisor(AiClient aiClient) {
        this.aiClient = aiClient;
    }

    public boolean aiAvailable() {
        return aiClient.available();
    }

    /** AI 优先，失败或未配置时回落到规则生成。 */
    public List<Proposition> propose(String systemName, String url, List<ElementCandidate> elements) {
        if (elements == null || elements.isEmpty()) return List.of();
        if (aiClient.available()) {
            try {
                List<Proposition> proposal = fromAi(systemName, url, elements);
                if (!proposal.isEmpty()) return proposal;
            } catch (RuntimeException e) {
                log.warn("AI 自动建树失败，改用规则分组：{}", e.getMessage());
            }
        }
        return heuristics(systemName, elements);
    }

    /**
     * 截图选区识别：给视觉模型看一块截图，产出「区域名 + 功能点列表」。
     * 返回的提案已经接好父子关系（模块 → 区域名 → 各功能点），可直接进建树预览。
     */
    public RegionProposal proposeFromRegion(byte[] image, String systemName, String pageUrl, String fallbackName) {
        return proposeFromRegion(image, List.of(), null, systemName, pageUrl, fallbackName);
    }

    /**
     * 带用户提示与参考图的识别。提示词是提升准确率的主要手段：
     * 用户知道这块是「数据源类型选择弹窗」，模型只看图猜不出来。
     *
     * @param references 用户补充的参考图（同页面的其他部分），可为空
     * @param hint       用户写的识别提示，可为空
     */
    public RegionProposal proposeFromRegion(byte[] image, List<byte[]> references, String hint,
                                            String systemName, String pageUrl, String fallbackName) {
        return proposeFromRegion(List.of(image), references, hint, systemName, pageUrl, fallbackName);
    }

    /**
     * 多选区识别：用户可以在同一张截图上框出几块互不相连的区域（搜索栏在右上、操作按钮在列表右侧），
     * 每块单独裁出来，一起交给同一个视觉模型，让它们进同一份草稿。
     *
     * @param regions    框选出来的区域裁剪图，按框选顺序；空则直接回落
     * @param references 用户补充的参考图（同页面的其他部分），可为空
     * @param hint       用户写的识别提示，可为空
     */
    public RegionProposal proposeFromRegion(List<byte[]> regions, List<byte[]> references, String hint,
                                            String systemName, String pageUrl, String fallbackName) {
        List<byte[]> picked = regions == null ? List.of()
                : regions.stream().filter(bytes -> bytes != null && bytes.length > 0).toList();
        if (aiClient.available() && !picked.isEmpty()) {
            try {
                RegionProposal proposal = regionFromAi(picked, references, hint, systemName, pageUrl);
                if (proposal != null && !proposal.nodes().isEmpty()) return proposal;
            } catch (RuntimeException e) {
                log.warn("截图视觉识别失败，改用占位节点：{}", e.getMessage());
            }
        }
        String rootName = systemName == null || systemName.isBlank() ? "被测系统" : trim(systemName, 24);
        String regionName = fallbackName == null || fallbackName.isBlank() ? "截图选区" : trim(fallbackName, 24);
        List<Proposition> nodes = new ArrayList<>();
        nodes.add(new Proposition("root", rootName, NodeType.MODULE, null, null));
        nodes.add(new Proposition("region", regionName, NodeType.TITLE1, "root", null));
        nodes.add(new Proposition("point", regionName + "功能验证", NodeType.POINT, "region", null));
        return new RegionProposal(regionName, "未识别（AI 未配置或模型不支持图片），名称与层级可手动调整", nodes);
    }

    private RegionProposal regionFromAi(List<byte[]> regions, List<byte[]> references, String hint,
                                        String systemName, String pageUrl) {
        List<byte[]> pictures = new ArrayList<>(regions);
        int regionCount = pictures.size();
        if (references != null) {
            for (byte[] reference : references) {
                if (reference != null && reference.length > 0) pictures.add(reference);
            }
        }
        String text = aiClient.chatVision(REGION_PROMPT,
                regionPrompt(systemName, pageUrl, hint, regionCount, pictures.size() - regionCount),
                pictures, "image/png");
        return parseRegion(text, systemName, null);
    }

    /** 识别请求的正文：系统/页面 + 图片说明 + 用户提示。提示要原样带上，模型才知道该怎么分组。 */
    static String regionPrompt(String systemName, String pageUrl, String hint,
                               int regionCount, int referenceCount) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("系统：").append(systemName == null ? "" : systemName)
                .append("\n页面：").append(pageUrl == null ? "" : pageUrl);
        if (regionCount > 1) {
            prompt.append("\n共 ").append(regionCount + Math.max(0, referenceCount)).append(" 张图：前 ")
                    .append(regionCount).append(" 张是用户在同一页面上框选出来的 ")
                    .append(regionCount).append(" 块互不相邻的区域（按框选顺序，图上角标 1、2…）。"
                            + "每块都是需要识别的对象，请把它们当作同一个界面的几部分合起来理解："
                            + "同一控件在多张里重复出现只建一次，不要把同一块区域拆成两套节点。");
        }
        if (referenceCount > 0) {
            prompt.append("\n最后 ").append(referenceCount).append(" 张是用户补充的参考图（同页面的其他部分），"
                    + "只用来帮助理解上下文，不要给参考图里的控件建功能点。");
        }
        if (hint != null && !hint.isBlank()) {
            prompt.append("\n用户提示（优先遵守）：").append(hint.trim());
        }
        if (regionCount == 1) {
            prompt.append("\n请只针对第 1 张图里真实可见的区域与控件做识别。");
        } else {
            prompt.append("\n请只针对前 ").append(regionCount).append(" 张图里真实可见的区域与控件做识别。");
        }
        return prompt.toString();
    }

    /** 把模型返回的 JSON 变成草稿节点；分组（type=二级标题 或带 children）多建一层标题。 */
    RegionProposal parseRegion(String text, String systemName, String fallbackName) throws IllegalStateException {
        JsonNode root;
        try {
            root = mapper.readTree(stripFences(text));
        } catch (Exception e) {
            throw new IllegalStateException("视觉识别结果不是合法 JSON：" + e.getMessage(), e);
        }
        String regionName = trim(root.path("name").asText(""), 24);
        if (regionName.isBlank()) {
            regionName = fallbackName == null || fallbackName.isBlank() ? "截图区域" : trim(fallbackName, 24);
        }
        String summary = trim(root.path("summary").asText(""), 120);
        String rootName = systemName == null || systemName.isBlank() ? "被测系统" : trim(systemName, 24);
        List<Proposition> nodes = new ArrayList<>();
        nodes.add(new Proposition("root", rootName, NodeType.MODULE, null, null));
        nodes.add(new Proposition("region", regionName, NodeType.TITLE1, "root", null));
        int index = 1;
        for (JsonNode point : root.path("points")) {
            String name = trim(point.path("name").asText(""), 24);
            if (name.isBlank()) continue;
            JsonNode children = point.path("children");
            boolean grouped = "二级标题".equals(point.path("type").asText(""))
                    || (children.isArray() && !children.isEmpty());
            String id = "p" + index++;
            if (grouped) {
                // 用户要求按组归类时，组名做二级标题，组内控件做功能点，避免把组名和控件平铺成一层。
                nodes.add(new Proposition(id, name, NodeType.TITLE2, "region", null));
                for (JsonNode child : children) {
                    String childName = trim(child.path("name").asText(""), 24);
                    if (childName.isBlank()) continue;
                    nodes.add(new Proposition("p" + index++, childName,
                            parseType(child.path("type").asText("功能点")), id, null));
                    if (index > 14) break;
                }
            } else {
                nodes.add(new Proposition(id, name, parseType(point.path("type").asText("功能点")),
                        "region", null));
            }
            if (index > 14) break;
        }
        if (nodes.size() <= 2) {
            nodes.add(new Proposition("p1", regionName + "功能验证", NodeType.POINT, "region", null));
        }
        return new RegionProposal(regionName, summary, nodes);
    }

    private static final String REGION_PROMPT = """
            你是资深软件测试分析师，现在只看到网页的一张局部截图。
            请识别截图中的业务区域和可见控件，输出可用于功能测试的功能点。

            规则：
            1. 区域名和功能点名都用截图里的中文文案，不要自造业务名词，名称 ≤ 12 字。
            2. 只列截图中真实存在的业务控件；分页（1/2/3、上一页、下一页）、纯图标、装饰元素不要列。
            3. 同一类控件合并成一个功能点（例如多个状态统计表格算一个）。
            4. 功能点 3~8 个为宜。
            5. 用户提示优先：提示里给的区域性质、分组方式、该列什么/不该列什么，一律按提示来；
               但提示提到的内容在截图里找不到时不要编，宁可不列。
            6. 提示要求分组时，用 name 写组名（例如「关系型数据库」），points 里放该组下的功能点；
               不需要分组就省略。

            只输出 JSON，不要 Markdown：
            {"name":"区域名称","summary":"一句话说明这块区域的作用",
             "points":[{"name":"功能点名称","type":"功能点","reason":"为什么值得测"},
                       {"name":"分组名称","type":"二级标题","children":[{"name":"组内功能点","type":"功能点"}]}]}
            type 只能是：二级标题（分组）/ 三级标题 / 功能点；没有分组就只写一层的 points。
            """;

    /** 截图选区识别结果。 */
    public record RegionProposal(String name, String summary, List<Proposition> nodes) {}

    private List<Proposition> fromAi(String systemName, String url, List<ElementCandidate> elements) {
        List<Map<String, Object>> compact = new ArrayList<>();
        for (ElementCandidate element : elements.subList(0, Math.min(elements.size(), MAX_ELEMENTS))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", element.getId());
            row.put("name", trim(element.getName(), 40));
            row.put("kind", element.getKind());
            row.put("component", trim(element.getComponent(), 24));
            row.put("path", trim(element.getSemanticPath(), 120));
            compact.add(row);
        }
        String prompt;
        try {
            prompt = "系统：" + (systemName == null ? "" : systemName) + "\n页面：" + (url == null ? "" : url)
                    + "\n元素：\n" + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(compact);
        } catch (Exception e) {
            throw new IllegalStateException("构造建树请求失败：" + e.getMessage(), e);
        }
        String text = aiClient.chat(SYSTEM_PROMPT, prompt);
        JsonNode root;
        try {
            root = mapper.readTree(stripFences(text));
        } catch (Exception e) {
            throw new IllegalStateException("AI 建树结果不是合法 JSON：" + e.getMessage(), e);
        }
        List<Proposition> nodes = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode node : root.path("nodes")) {
            String id = node.path("id").asText("");
            String name = trim(node.path("name").asText(""), 24);
            if (id.isBlank() || name.isBlank() || ids.contains(id)) continue;
            ids.add(id);
            nodes.add(new Proposition(id, name, parseType(node.path("type").asText("")),
                    emptyToNull(node.path("parent").asText("")),
                    emptyToNull(node.path("element").asText(""))));
            if (nodes.size() >= MAX_NODES) break;
        }
        if (nodes.isEmpty()) return List.of();
        // 父级不存在时挂到顶层；没有模块时补一个，保证树根统一。
        Set<String> known = new HashSet<>();
        nodes.forEach(n -> known.add(n.id()));
        List<Proposition> fixed = new ArrayList<>();
        boolean hasModule = false;
        for (Proposition node : nodes) {
            String parent = node.parentId() != null && known.contains(node.parentId()) ? node.parentId() : null;
            if (node.type() == NodeType.MODULE && parent == null) hasModule = true;
            fixed.add(new Proposition(node.id(), node.name(), node.type(), parent, node.elementId()));
        }
        if (!hasModule) {
            String rootName = systemName == null || systemName.isBlank() ? "被测系统" : trim(systemName, 24);
            fixed.addFirst(new Proposition("root-" + Math.abs(rootName.hashCode()),
                    rootName, NodeType.MODULE, null, null));
            String rootId = fixed.getFirst().id();
            for (int i = 1; i < fixed.size(); i++) {
                Proposition node = fixed.get(i);
                if (node.parentId() == null) fixed.set(i, new Proposition(node.id(), node.name(), node.type(), rootId, node.elementId()));
            }
        }
        return breakCycles(fixed);
    }

    /** 规则兜底：菜单元素做一级标题，其余按组件（没有组件就按控件类型）分组，组下每个元素一个功能点。 */
    public List<Proposition> heuristics(String systemName, List<ElementCandidate> elements) {
        List<Proposition> out = new ArrayList<>();
        String moduleId = "m0";
        String moduleName = systemName == null || systemName.isBlank() ? "被测系统" : trim(systemName, 24);
        out.add(new Proposition(moduleId, moduleName, NodeType.MODULE, null, null));

        List<ElementCandidate> navItems = new ArrayList<>();
        List<ElementCandidate> rest = new ArrayList<>();
        for (ElementCandidate element : elements) {
            if (isNavigation(element) && navItems.size() < 12) navItems.add(element);
            else rest.add(element);
        }
        int seq = 1;
        for (ElementCandidate nav : navItems) {
            String name = label(nav);
            if (name.isBlank()) continue;
            out.add(new Proposition("t" + seq++, name, NodeType.TITLE1, moduleId, nav.getId()));
        }

        Map<String, List<ElementCandidate>> groups = new LinkedHashMap<>();
        for (ElementCandidate element : rest) {
            String key = element.getComponent() == null || element.getComponent().isBlank()
                    ? kindLabel(element.getKind()) : trim(element.getComponent(), 20);
            if (key.isBlank()) key = "其他";
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(element);
        }
        int groupIndex = 0;
        for (Map.Entry<String, List<ElementCandidate>> entry : groups.entrySet()) {
            if (groupIndex++ >= 10 || out.size() >= MAX_NODES) break;
            String groupId = "g" + seq++;
            out.add(new Proposition(groupId, entry.getKey(), NodeType.TITLE1, moduleId, null));
            int pointCount = 0;
            for (ElementCandidate element : entry.getValue()) {
                if (pointCount++ >= 10 || out.size() >= MAX_NODES) break;
                String name = label(element);
                if (name.isBlank()) continue;
                out.add(new Proposition("p" + seq++, trim(name, 24), NodeType.POINT, groupId, element.getId()));
            }
        }
        return out;
    }

    private static boolean isNavigation(ElementCandidate element) {
        String path = element.getSemanticPath() == null ? "" : element.getSemanticPath().toLowerCase(Locale.ROOT);
        String kind = element.getKind() == null ? "" : element.getKind().toLowerCase(Locale.ROOT);
        boolean navPath = path.contains("nav") || path.contains("menu") || path.contains("header")
                || path.contains("tabbar") || path.contains("sidebar");
        boolean clickable = kind.contains("link") || kind.contains("button") || kind.contains("a");
        return navPath && clickable && label(element).length() <= 12;
    }

    private static String label(ElementCandidate element) {
        String name = element.getName() == null ? "" : element.getName().trim();
        if (name.isEmpty()) name = element.getComponent() == null ? "" : element.getComponent().trim();
        return name;
    }

    private static String kindLabel(String kind) {
        if (kind == null) return "其他";
        return switch (kind.toLowerCase(Locale.ROOT)) {
            case "input", "text", "search" -> "输入框";
            case "button", "submit" -> "按钮";
            case "link", "a" -> "链接";
            case "select", "combobox" -> "下拉框";
            case "checkbox" -> "复选框";
            case "radio" -> "单选框";
            case "date", "datetime" -> "日期控件";
            case "textarea" -> "文本域";
            default -> "其他";
        };
    }

    private static NodeType parseType(String text) {
        String value = text == null ? "" : text.trim();
        if (value.contains("模块")) return NodeType.MODULE;
        if (value.contains("一级")) return NodeType.TITLE1;
        if (value.contains("二级")) return NodeType.TITLE2;
        if (value.contains("三级")) return NodeType.TITLE3;
        return NodeType.POINT;
    }

    /** 断掉自引用与环，避免预览树渲染不出来。 */
    private static List<Proposition> breakCycles(List<Proposition> nodes) {
        Map<String, Proposition> byId = new LinkedHashMap<>();
        nodes.forEach(n -> byId.putIfAbsent(n.id(), n));
        List<Proposition> out = new ArrayList<>();
        for (Proposition node : byId.values()) {
            Set<String> seen = new HashSet<>();
            String parent = node.parentId();
            boolean cycle = false;
            while (parent != null && seen.add(parent)) {
                Proposition up = byId.get(parent);
                if (up == null) { parent = null; break; }
                if (up.id().equals(node.id())) { cycle = true; break; }
                parent = up.parentId();
            }
            out.add(cycle ? new Proposition(node.id(), node.name(), node.type(), null, node.elementId()) : node);
        }
        return out;
    }

    private static String trim(String value, int max) {
        if (value == null) return "";
        String v = value.replaceAll("\\s+", " ").trim();
        return v.length() <= max ? v : v.substring(0, max);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String stripFences(String text) {
        String t = text == null ? "" : text.trim();
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start >= 0 && end > start) t = t.substring(start, end + 1);
        return t;
    }
}
