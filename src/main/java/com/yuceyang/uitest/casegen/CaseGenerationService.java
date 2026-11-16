package com.yuceyang.uitest.casegen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuceyang.uitest.ai.AiClient;
import com.yuceyang.uitest.element.ElementCandidate;
import com.yuceyang.uitest.screenshot.Screenshot;
import com.yuceyang.uitest.screenshot.ScreenshotStatus;
import com.yuceyang.uitest.storage.WorkspaceSnapshot;
import com.yuceyang.uitest.system.SystemUnderTest;
import com.yuceyang.uitest.testcase.TestCase;
import com.yuceyang.uitest.tree.NodeType;
import com.yuceyang.uitest.tree.TreeNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 用例生成：与原 Python 工具同源。
 * <ul>
 *   <li>规则生成：case_builder v3.0（页面加载 / 条件查询 / 格式异常 / 未登录权限），编号 GN-00001 起（五位）。</li>
 *   <li>AI 生成：强制「只查询/只读」系统提示词，要求严格 JSON，失败时回落到规则生成。</li>
 * </ul>
 */
@Service
public class CaseGenerationService {
    public static final String SYSTEM_PROMPT = """
            你是资深软件测试专家，根据「功能点 + 已识别的界面元素」设计可执行的界面测试用例。

            硬性约束（不可违反）：
            1. 步骤和预期里出现的每个控件，必须能在「元素清单」或「随附页面截图」里找到依据。
               两者都没有的检索框、查询按钮、分页、列表、导出、日期时间、密码等控件，
               禁止写进标题、步骤或预期；界面上没有的功能一律不得虚构。
            2. 输入类元素（input/textarea）必须逐个覆盖：每个字段至少 1 条针对该字段的用例，用例标题与步骤里
               写清字段名（与清单中的名称完全一致），覆盖正常输入、必填/空值、长度或格式边界、特殊字符。
            3. 预期结果必须针对该元素的具体行为（回显、必填提示、长度限制、选项列表、按钮反馈），
               不要写「功能正常」「数据正确」这类空话。
            4. 允许写「填写表单 → 点击按钮」的界面操作步骤，但必须使用明确的测试数据（如 自动化测试_001），
               不得要求删除数据库、修改配置或其它破坏性操作。
            5. 既没有元素清单、截图里也找不到对应区域的功能点：只写一条「界面加载与入口可达」用例，
               明确写「待补充元素识别」，不得编造字段名凑条数。
            6. 「data（携带参数 / 测试数据）」只能来自提示词里该功能点的 requests：参数名与真实值照抄，
               query 参数与请求体字段逐条写成「参数名=值」，method、url 也取自同一条请求。
               没有能对应上的请求就写「未捕获到该操作的接口请求」。禁止把界面状态（「已选项：default」）、
               示例值或臆造字段名写进 data。
            7. 仅输出 JSON，不要 Markdown 围栏，不要额外解释。

            输出 JSON 结构：
            {
              "cases": [
                {
                  "point_id": "功能点ID",
                  "title": "用例标题",
                  "module": "模块名",
                  "priority": "P0|P1|P2|P3",
                  "category": "positive|negative|boundary|auth|performance",
                  "preconditions": "前置条件",
                  "steps": "操作步骤（只读）",
                  "expected": "预期结果",
                  "data": "测试数据/参数",
                  "method": "GET",
                  "url": "相对或完整URL",
                  "auth_notes": "鉴权说明",
                  "ai_rationale": "设计理由，一句话"
                }
              ],
              "summary": "总体设计说明"
            }
            """;

    /**
     * @param liveSessionId 当前正在浏览的会话；功能点自己的会话没抓到接口时，用它兜底
     *                      （用户常常是先在同一会话里点过弹窗，再回来生成用例）。
     */
    public record Options(boolean includeNegative, boolean includeAuth, int minPerPoint, int maxPerPoint,
                          String extraRequirement, String screenshotId, String url, String liveSessionId) {
        public Options(boolean includeNegative, boolean includeAuth, int minPerPoint, int maxPerPoint,
                       String extraRequirement, String screenshotId, String url) {
            this(includeNegative, includeAuth, minPerPoint, maxPerPoint, extraRequirement, screenshotId, url, null);
        }
        public int lower() { return Math.max(1, Math.min(minPerPoint, maxPerPoint)); }
        public int upper() { return Math.max(lower(), maxPerPoint); }
    }

    public record Result(List<TestCase> cases, String summary, boolean aiUsed, String note) {}

    record Fields(String pointId, String pointName, String moduleCat, String titleL1,
                          String titleL2, String deepPath, String testTrack, String designRequirement,
                          String surface) {}

    private static final Logger log = LoggerFactory.getLogger(CaseGenerationService.class);

    private final AiClient aiClient;
    private final com.yuceyang.uitest.screenshot.ScreenshotFileStore screenshotFileStore;
    /** 浏览器里真实抓到的接口调用；没有它「携带参数」就只能靠模型编。 */
    private final com.yuceyang.uitest.browser.NetworkLog networkLog;
    private final ObjectMapper mapper = new ObjectMapper();

    public CaseGenerationService(AiClient aiClient,
                                 com.yuceyang.uitest.screenshot.ScreenshotFileStore screenshotFileStore) {
        this(aiClient, screenshotFileStore, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CaseGenerationService(AiClient aiClient,
                                 com.yuceyang.uitest.screenshot.ScreenshotFileStore screenshotFileStore,
                                 com.yuceyang.uitest.browser.NetworkLog networkLog) {
        this.aiClient = aiClient;
        this.screenshotFileStore = screenshotFileStore;
        this.networkLog = networkLog;
    }

    public Result generate(WorkspaceSnapshot workspace, String systemId, List<String> pointIds, Options options) {
        String sysName = workspace.systems.stream()
                .filter(s -> systemId.equals(s.getId())).map(SystemUnderTest::getName)
                .findFirst().orElse("被测系统");
        Map<String, TreeNode> byId = new HashMap<>();
        workspace.nodes.stream()
                .filter(n -> systemId.equals(n.getSystemId()) && n.getDeletedAt() == null)
                .forEach(n -> byId.put(n.getId(), n));
        List<TreeNode> points = byId.values().stream()
                .filter(n -> n.getNodeType() == NodeType.POINT)
                .filter(n -> pointIds == null || pointIds.isEmpty() || pointIds.contains(n.getId()))
                .sorted(Comparator.comparingInt(TreeNode::getSortOrder).thenComparing(TreeNode::getName))
                .toList();
        if (points.isEmpty()) {
            return new Result(List.of(), "", false, "没有可生成用例的功能点，请先在功能点树中标记或新增功能点");
        }
        Map<String, ElementCandidate> elements = new LinkedHashMap<>();
        workspace.elements.stream()
                .filter(e -> e.getDeletedAt() == null && !e.isArchived())
                .forEach(e -> elements.put(e.getId(), e));
        Map<String, Fields> fieldsByPoint = new LinkedHashMap<>();
        for (TreeNode point : points) {
            fieldsByPoint.put(point.getId(), resolveFields(point, byId, sysName));
        }
        Map<String, List<ElementCandidate>> elementsByPoint =
                buildPointElements(points, byId, elements, options.extraRequirement());
        // 每个功能点配它自己那一页的截图：同一次生成里可能混着不同页面的功能点，
        // 共用一张图会让模型照着别人的页面写步骤。同一张图只从磁盘读一次。
        Map<String, String> shotByPoint = new LinkedHashMap<>();
        Map<String, byte[]> imageCache = new HashMap<>();
        for (TreeNode point : points) {
            String shot = resolveScreenshotId(workspace, systemId, options.screenshotId(),
                    elementsByPoint.getOrDefault(point.getId(), List.of()));
            shotByPoint.put(point.getId(), shot);
            if (shot != null) imageCache.computeIfAbsent(shot, id -> loadScreenshotBytes(workspace, id));
        }
        String screenshotId = resolveScreenshotId(workspace, systemId, options.screenshotId(),
                elementsByPoint.values().stream().flatMap(List::stream).toList());
        // 每个功能点能用的真实接口样本：按它那张截图所属会话（其次元素所属会话）去取。
        Map<String, List<com.yuceyang.uitest.browser.NetworkExchange>> callsByPoint =
                resolveNetworkCalls(workspace, points, shotByPoint, elementsByPoint, options.url(),
                        options.liveSessionId());
        int startNo = nextCaseSequence(workspace);
        boolean anyImage = imageCache.values().stream().anyMatch(bytes -> bytes != null && bytes.length > 0);
        int callCount = callsByPoint.values().stream().mapToInt(List::size).sum();

        if (aiClient.available()) {
            try {
                AiResult ai = generateWithAi(points, fieldsByPoint, elementsByPoint, elements,
                        options, sysName, shotByPoint, imageCache, callsByPoint);
                List<TestCase> cases = ai.cases();
                if (!cases.isEmpty()) {
                    bindSequenceAndScreenshot(cases, shotByPoint, screenshotId, startNo);
                    return new Result(cases, anyImage ? "AI 已结合页面截图与识别到的界面元素生成"
                            : "AI 已按识别到的界面元素生成", true,
                            "模型：" + aiClient.model() + (anyImage ? "，已附带页面截图" : "，仅文本")
                                    + (callCount > 0 ? "，已附带 " + callCount + " 条真实接口样本" : "，未捕获到接口请求")
                                    + "，共 " + cases.size() + " 条"
                                    + (ai.toppedUp() > 0
                                    ? "（AI 只写了 " + (cases.size() - ai.toppedUp()) + " 条，"
                                    + ai.toppedUp() + " 条按「下限」用规则补齐）" : "")
                                    + (ai.shortfalls().isEmpty() ? ""
                                    : "；未达下限：" + String.join("、", ai.shortfalls())
                                    + "（该功能点没挂元素、截图里也没有对应区域，按规则不编造）"));
                }
                return fallback(points, fieldsByPoint, elementsByPoint, elements, options, sysName, screenshotId,
                        startNo, "AI 未返回可用用例，已回落到规则生成", callsByPoint);
            } catch (RuntimeException e) {
                log.warn("AI 用例生成失败，回落到规则模板：{}", e.getMessage());
                return fallback(points, fieldsByPoint, elementsByPoint, elements, options, sysName, screenshotId,
                        startNo, "AI 调用失败（" + e.getMessage() + "），已回落到规则生成", callsByPoint);
            }
        }
        return fallback(points, fieldsByPoint, elementsByPoint, elements, options, sysName, screenshotId, startNo,
                "未配置 AI，使用规则生成（case_builder v3.0）", callsByPoint);
    }

    /**
     * 每个功能点拿到的「界面元素上下文」：
     * <ol>
     *   <li>功能点自身挂的元素；老数据里功能点下可能还有子节点，子孙挂的元素一并算进来；</li>
     *   <li>额外人工要求点名、但还没挂到树上的元素——用户写了「再添加密码输入框的用例」，
     *       密码就不能因为「功能点只绑了用户名」而进不了提示词。</li>
     * </ol>
     * 不再拿「同一次浏览会话里还没归属的其它元素」兜底：一次会话会跨多个页面/弹窗，
     * 兜底出来的字段往往属于别的界面，正是「用例写的内容跟页面完全不符」的来源。
     * 功能点自己没挂元素时，宁可只给截图让模型看，也不编造字段。
     */
    private Map<String, List<ElementCandidate>> buildPointElements(List<TreeNode> points, Map<String, TreeNode> byId,
                                                                   Map<String, ElementCandidate> elements,
                                                                   String extraRequirement) {
        Map<String, List<TreeNode>> childrenOf = new HashMap<>();
        for (TreeNode node : byId.values()) {
            if (node.getParentId() != null) {
                childrenOf.computeIfAbsent(node.getParentId(), k -> new ArrayList<>()).add(node);
            }
        }
        Map<String, String> ownerOf = new HashMap<>();
        for (TreeNode node : byId.values()) {
            if (node.getElementId() != null) ownerOf.putIfAbsent(node.getElementId(), node.getId());
        }
        List<ElementCandidate> unowned = new ArrayList<>();
        for (ElementCandidate element : elements.values()) {
            if (element == null || blank(element.getName())) continue;
            if (!ownerOf.containsKey(element.getId())) unowned.add(element);
        }
        unowned.sort(Comparator.comparingInt(CaseGenerationService::kindRank)
                .thenComparing(ElementCandidate::getName));

        Map<String, List<ElementCandidate>> out = new LinkedHashMap<>();
        for (TreeNode point : points) {
            LinkedHashMap<String, ElementCandidate> linked = new LinkedHashMap<>();
            for (ElementCandidate element : subtreeElements(point, childrenOf, elements)) {
                linked.putIfAbsent(element.getId(), element);
            }
            // 只补「额外要求点名、但还没挂到树上」的元素。功能点自己没元素时不再拿同会话其它元素凑数：
            // 一次浏览会话会跨多个页面，凑进来的字段往往属于别的界面，写出来的用例就是凭空捏造。
            for (ElementCandidate element : unowned) {
                if (!namedInExtra(element, extraRequirement)) continue;
                if (linked.size() >= 12) break;
                linked.putIfAbsent(element.getId(), element);
            }
            out.put(point.getId(), new ArrayList<>(linked.values()));
        }
        return out;
    }

    private static int kindRank(ElementCandidate element) {
        String kind = blank(element.getKind()) ? "other" : element.getKind();
        return switch (kind) {
            case "input", "textarea" -> 0;
            case "select" -> 1;
            case "checkbox", "radio" -> 2;
            case "button" -> 3;
            default -> 4;
        };
    }

    private static List<ElementCandidate> subtreeElements(TreeNode node, Map<String, List<TreeNode>> childrenOf,
                                                          Map<String, ElementCandidate> elements) {
        List<ElementCandidate> out = new ArrayList<>();
        java.util.ArrayDeque<TreeNode> stack = new java.util.ArrayDeque<>();
        stack.push(node);
        int guard = 0;
        while (!stack.isEmpty() && guard++ < 20000) {
            TreeNode current = stack.pop();
            if (current.getElementId() != null) {
                ElementCandidate element = elements.get(current.getElementId());
                if (element != null) out.add(element);
            }
            for (TreeNode kid : childrenOf.getOrDefault(current.getId(), List.of())) stack.push(kid);
        }
        return out;
    }

    /** 元素名去掉「请输入/请选择」与「输入框/按钮」这类壳，剩下的才是字段本名（密码、用户名、登录）。 */
    static String elementCore(String name) {
        if (blank(name)) return "";
        String core = name.replaceAll("[\\s\\u3000]+", "");
        for (String prefix : List.of("请输入", "请选择", "请填写", "输入", "选择", "填写")) {
            if (core.startsWith(prefix) && core.length() > prefix.length()) {
                core = core.substring(prefix.length());
                break;
            }
        }
        for (String suffix : List.of("输入框", "输入区", "下拉框", "选择框", "文本框", "复选框", "单选框", "按钮", "链接", "框")) {
            if (core.endsWith(suffix) && core.length() > suffix.length()) {
                core = core.substring(0, core.length() - suffix.length());
                break;
            }
        }
        return core.trim();
    }

    /** 额外人工要求里是否点名了这个元素（用户写了「密码输入框」，元素名「请输入密码」也要算点名）。 */
    static boolean namedInExtra(ElementCandidate element, String extra) {
        if (element == null || blank(extra)) return false;
        String core = elementCore(element.getName());
        if (core.length() < 2) return false;
        return extra.replaceAll("[\\s\\u3000]+", "").contains(core);
    }

    private static boolean mentions(TestCase testCase, String core) {
        if (blank(core)) return false;
        return joinedText(testCase).contains(core);
    }

    private static String joinedText(TestCase testCase) {
        return String.join("|", nullSafe(testCase.getTitle()), nullSafe(testCase.getSteps()),
                nullSafe(testCase.getExpectedResult()), nullSafe(testCase.getPreconditions()));
    }

    /**
     * 每个功能点可用的接口样本：优先取它那张截图所属会话里的调用（截图就是那一页），
     * 其次取它自己元素所属会话的调用；都没有就返回空，提示词里据此写「未捕获到接口请求」。
     */
    private Map<String, List<com.yuceyang.uitest.browser.NetworkExchange>> resolveNetworkCalls(
            WorkspaceSnapshot workspace, List<TreeNode> points, Map<String, String> shotByPoint,
            Map<String, List<ElementCandidate>> elementsByPoint, String targetUrl, String liveSessionId) {
        Map<String, List<com.yuceyang.uitest.browser.NetworkExchange>> out = new LinkedHashMap<>();
        if (networkLog == null) return out;
        Map<String, com.yuceyang.uitest.screenshot.Screenshot> shots = new HashMap<>();
        for (com.yuceyang.uitest.screenshot.Screenshot shot : workspace.screenshots) shots.put(shot.getId(), shot);
        String host = hostOf(targetUrl);
        for (TreeNode point : points) {
            LinkedHashSet<String> sessionIds = new LinkedHashSet<>();
            String shotId = shotByPoint.get(point.getId());
            com.yuceyang.uitest.screenshot.Screenshot shot = shotId == null ? null : shots.get(shotId);
            if (shot != null && shot.getSessionId() != null) sessionIds.add(shot.getSessionId());
            for (ElementCandidate element : elementsByPoint.getOrDefault(point.getId(), List.of())) {
                if (element.getSessionId() != null) sessionIds.add(element.getSessionId());
            }
            List<com.yuceyang.uitest.browser.NetworkExchange> calls = new ArrayList<>();
            for (String sessionId : sessionIds) {
                for (com.yuceyang.uitest.browser.NetworkExchange call
                        : networkLog.apiCalls(sessionId, 8, host)) {
                    if (calls.size() >= 8) break;
                    calls.add(call);
                }
                if (calls.size() >= 8) break;
            }
            if (calls.isEmpty() && liveSessionId != null && !sessionIds.contains(liveSessionId)) {
                calls.addAll(networkLog.apiCalls(liveSessionId, 8, host));
            }
            out.put(point.getId(), calls);
        }
        return out;
    }

    private static String hostOf(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            return java.net.URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 展示项用例：功能点里只有只读展示内容（仪表盘读数、状态标识、时间、链接文字），没有可操作元素可测。
     * 这种功能点交给 AI 或元素规则都只会写出「页面加载」一条，所以直接按展示项出：
     * 每项一条「展示与数据一致性」，再加一条整块的「刷新与异常态」。
     *
     * @param items 展示项名称，按界面上的顺序；重复与空名会被去掉
     * @param screenshotId 这些展示项所在的那张截图：挂到用例上，用例详情里能直接看到原界面
     * @param liveSessionId 当前浏览器会话：有的话用它的接口调用来填「携带参数」（拿不到就留空，不编）
     */
    public List<TestCase> displayCases(WorkspaceSnapshot workspace, String systemId, String pointId,
                                       List<String> items, String url, String screenshotId, String liveSessionId) {
        Map<String, TreeNode> byId = new HashMap<>();
        workspace.nodes.stream()
                .filter(n -> systemId.equals(n.getSystemId()) && n.getDeletedAt() == null)
                .forEach(n -> byId.put(n.getId(), n));
        TreeNode point = pointId == null ? null : byId.get(pointId);
        List<String> names = items == null ? List.of() : items.stream()
                .filter(name -> !blank(name)).map(String::trim).distinct().toList();
        if (point == null || names.isEmpty()) return List.of();
        String sysName = workspace.systems.stream()
                .filter(s -> systemId.equals(s.getId())).map(SystemUnderTest::getName)
                .findFirst().orElse("被测系统");
        Fields f = resolveFields(point, byId, sysName);
        List<TestCase> out = new ArrayList<>();
        for (String item : names) {
            out.add(caseOf(point, f, "「" + item + "」展示与数据一致性验证",
                    "1.已使用测试账号登录；2.已进入「" + f.deepPath() + "」；"
                            + "3.已知「" + item + "」的取值来源（接口或后端指标）",
                    "1.查看「" + item + "」的展示位置，记录显示的文本、数值与单位；"
                            + "2.通过接口或后台核对同一指标的当前值；"
                            + "3.刷新页面（或等一次自动刷新）后再次查看",
                    "1.「" + item + "」显示明确的文本或数值，单位与格式符合设计，没有空白、undefined、NaN；"
                            + "2.界面显示值与后端实际值一致（在设计约定的精度范围内）；"
                            + "3.刷新后数值同步更新，不出现报错或占位空白",
                    "positive", "P1", "P1；展示型-只读内容校验", url));
        }
        out.add(caseOf(point, f, f.pointName() + "展示区刷新与异常态验证",
                "1.已登录；2.已进入「" + f.deepPath() + "」；3.可在测试环境模拟指标为空或接口异常",
                "1.记录展示区（" + String.join("、", names) + "）的当前显示；"
                        + "2.刷新页面，等待展示区加载完成；"
                        + "3.模拟指标为空或接口返回异常后再次刷新",
                "1.刷新后各展示项正常渲染，顺序与布局不错乱；"
                        + "2.指标为空时显示占位（如 - 或 暂无数据），不出现报错弹窗或整页异常；"
                        + "3.接口恢复后再次刷新能正常显示",
                "negative", "P2", "P2；展示型-刷新与异常态", url));
        // 展示项只读，没有点击动作，但「这些数值是从哪个接口来的」可以像规则用例一样填真实调用：
        // 优先用截图所属会话，其次用当前浏览器会话；都没有就留空（不拿页面地址冒充参数）。
        List<com.yuceyang.uitest.browser.NetworkExchange> calls = new ArrayList<>();
        if (networkLog != null) {
            String host = hostOf(url);
            String shotSession = workspace.screenshots.stream()
                    .filter(shot -> shot.getId().equals(screenshotId))
                    .map(Screenshot::getSessionId)
                    .filter(java.util.Objects::nonNull)
                    .findFirst().orElse(null);
            if (shotSession != null) calls.addAll(networkLog.apiCalls(shotSession, 8, host));
            if (calls.isEmpty() && liveSessionId != null && !liveSessionId.equals(shotSession)) {
                calls.addAll(networkLog.apiCalls(liveSessionId, 8, host));
            }
        }
        applyRuleCalls(out, calls);
        if (!blank(screenshotId)) {
            out.forEach(testCase -> testCase.setScreenshotId(screenshotId));
        }
        return out;
    }

    private Result fallback(List<TreeNode> points, Map<String, Fields> fieldsByPoint,
                            Map<String, List<ElementCandidate>> elementsByPoint,
                            Map<String, ElementCandidate> systemElements, Options options,
                            String sysName, String screenshotId, int startNo, String note,
                            Map<String, List<com.yuceyang.uitest.browser.NetworkExchange>> callsByPoint) {
        List<TestCase> cases = new ArrayList<>();
        for (TreeNode point : points) {
            List<TestCase> built = new ArrayList<>(buildRules(point, fieldsByPoint.get(point.getId()),
                    elementsByPoint.getOrDefault(point.getId(), List.of()), systemElements, options, sysName));
            applyRuleCalls(built, callsByPoint.getOrDefault(point.getId(), List.of()));
            cases.addAll(built);
        }
        bindSequenceAndScreenshot(cases, screenshotId, startNo);
        return new Result(cases, "按界面元素类型规则生成", false, note);
    }

    /**
     * 规则生成的用例拿不到「点击后到底调了哪个接口」，所以只在有接口样本时填第一个真实调用，
     * 并明确写成「参考接口」；没有样本就留空，不再把页面地址伪装成携带参数。
     */
    private void applyRuleCalls(List<TestCase> cases, List<com.yuceyang.uitest.browser.NetworkExchange> calls) {
        if (calls.isEmpty()) return;
        com.yuceyang.uitest.browser.NetworkExchange call = calls.get(0);
        for (TestCase testCase : cases) {
            if (!blank(testCase.getParamsText())) continue;
            testCase.setParamsText("参考接口（该页最近一次调用）：\n" + call.describe());
            testCase.setMethod(call.method());
            testCase.setUrl(call.url());
        }
    }

    /**
     * 规则生成（AI 不可用时的兜底）：用例完全由「功能点 + 已识别元素」驱动。
     * 元素是什么就测什么——输入框测输入与校验、按钮测点击反馈、下拉测选项枚举；
     * 只有功能点/元素名里确实出现「查询、列表、检索」这类证据时，才补查询与分页用例。
     * <p>额外人工要求里点名的元素优先出用例，且不会在按 target 截断时被切掉
     * （否则「再添加密码输入框的测试用例」会被「用户名」的 3 条占满名额）。</p>
     */
    private List<TestCase> buildRules(TreeNode node, Fields f, List<ElementCandidate> elements,
                                     Map<String, ElementCandidate> systemElements, Options options,
                                     String sysName) {
        RuleSet rules = ruleSet(node, f, elements, systemElements, options);
        int target = randomTarget(options);
        int keepRequired = Math.min(rules.required().size(), Math.max(0, target - 1));
        List<TestCase> picked = new ArrayList<>();
        picked.add(rules.pageLoad());
        picked.addAll(rules.required().subList(0, keepRequired));
        int room = target - picked.size();
        if (room > 0) picked.addAll(rules.others().subList(0, Math.min(room, rules.others().size())));
        // 名额还有剩（requiredCases 比名额多、或 otherCases 不够）时继续补，最终不超过 target
        int rest = target - picked.size();
        if (rest > 0 && keepRequired < rules.required().size()) {
            picked.addAll(rules.required().subList(keepRequired,
                    Math.min(rules.required().size(), keepRequired + rest)));
        }
        return picked;
    }

    /** 规则候选集：一条页面加载 + 额外要求点名的元素 + 其余元素与上下文；<b>未按 target 截断</b>。 */
    private record RuleSet(TestCase pageLoad, List<TestCase> required, List<TestCase> others) {
        List<TestCase> ordered() {
            List<TestCase> all = new ArrayList<>();
            all.add(pageLoad);
            all.addAll(required);
            all.addAll(others);
            return all;
        }
    }

    private RuleSet ruleSet(TreeNode node, Fields f, List<ElementCandidate> elements,
                            Map<String, ElementCandidate> systemElements, Options options) {
        String pointName = f.pointName();
        String surface = blank(f.surface()) ? pointName : f.surface();
        String url = options.url() == null ? "" : options.url();
        // 额外要求点名的元素先占名额，避免被「只取前 6 个元素」的规则挤掉
        List<ElementCandidate> fields = uniqueElements(elements,
                element -> namedInExtra(element, options.extraRequirement()));
        String elDesc = fields.stream().map(ElementCandidate::getName)
                .collect(java.util.stream.Collectors.joining("、"));

        List<ElementCandidate> required = new ArrayList<>();
        List<ElementCandidate> others = new ArrayList<>();
        for (ElementCandidate element : fields) {
            if (namedInExtra(element, options.extraRequirement())) required.add(element);
            else others.add(element);
        }
        // 功能点没挂任何元素（手建的）时，额外要求点名的元素就是它唯一的界面依据
        if (fields.isEmpty()) {
            for (ElementCandidate element : systemElements.values()) {
                if (namedInExtra(element, options.extraRequirement())) required.add(element);
            }
        }

        List<TestCase> requiredCases = new ArrayList<>();
        for (ElementCandidate element : required) {
            requiredCases.addAll(elementCases(node, f, surface, element, url));
        }
        List<TestCase> otherCases = new ArrayList<>();
        for (ElementCandidate element : others) {
            otherCases.addAll(elementCases(node, f, surface, element, url));
        }
        otherCases.addAll(contextCases(node, f, surface, pointName, fields, url));

        if (!options.includeNegative()) {
            requiredCases.removeIf(c -> "negative".equals(c.getCategory()) || "boundary".equals(c.getCategory()));
            otherCases.removeIf(c -> "negative".equals(c.getCategory()) || "boundary".equals(c.getCategory()));
        }
        if (options.includeAuth()) {
            otherCases.add(0, authCase(node, f, surface, url));
        }
        return new RuleSet(pageLoadCase(node, f, surface, elDesc, url), requiredCases, otherCases);
    }

    /** 「下限~上限」是每个功能点随机取一个目标条数，AI 与规则两条路都用同一个口径。 */
    private static int randomTarget(Options options) {
        return java.util.concurrent.ThreadLocalRandom.current()
                .nextInt(options.lower(), options.upper() + 1);
    }

    private static List<ElementCandidate> uniqueElements(List<ElementCandidate> elements) {
        return uniqueElements(elements, element -> false);
    }

    private static List<ElementCandidate> uniqueElements(List<ElementCandidate> elements,
                                                         java.util.function.Predicate<ElementCandidate> priority) {
        List<ElementCandidate> ordered = new ArrayList<>(elements);
        ordered.sort(Comparator.comparingInt((ElementCandidate element) -> priority.test(element) ? 0 : 1));
        Map<String, ElementCandidate> unique = new LinkedHashMap<>();
        for (ElementCandidate element : ordered) {
            if (element == null || blank(element.getName())) continue;
            String kind = blank(element.getKind()) ? "other" : element.getKind();
            unique.putIfAbsent(kind + "|" + element.getName().trim(), element);
            if (unique.size() >= 6) break;
        }
        return new ArrayList<>(unique.values());
    }

    private TestCase pageLoadCase(TreeNode node, Fields f, String surface, String elDesc, String url) {
        String steps = elDesc.isBlank()
                ? "1.进入「" + f.deepPath() + "」；2.观察界面是否正常渲染"
                : "1.进入「" + f.deepPath() + "」；2.核对界面上的关键元素：" + elDesc + "；3.逐个确认可见且可操作";
        String expect = elDesc.isBlank()
                ? "1.界面正常加载完成，无白屏与报错弹窗；2.页面无 JS 异常"
                : "1.界面加载完成，「" + elDesc + "」均完整可见；2.控件可点击/可输入，无遮挡与错位；3.页面无 JS 异常";
        return caseOf(node, f, surface + "界面加载与元素展示验证",
                "1.已使用测试账号登录；2.存在与「" + surface + "」相关的测试数据",
                steps, expect, "positive", "P0", "P0；界面加载与元素可见性", url);
    }

    private TestCase authCase(TreeNode node, Fields f, String surface, String url) {
        return caseOf(node, f, surface + "未登录访问权限控制验证",
                "1.清除会话或使用无痕窗口；2.保留原访问链接",
                "1.未登录直接访问「" + f.deepPath() + "」或刷新页面；2.观察跳转与提示",
                "1.跳转登录页或提示无权限；2.不展示业务数据；3.不改变服务端数据",
                "auth", "P0", "P0", url);
    }

    /** 按元素类型出用例：输入框测输入与校验、按钮测点击反馈、下拉测选项、勾选测状态。 */
    private List<TestCase> elementCases(TreeNode node, Fields f, String surface, ElementCandidate element, String url) {
        List<TestCase> out = new ArrayList<>();
        String name = element.getName().trim();
        String kind = blank(element.getKind()) ? "other" : element.getKind();
        String component = element.getComponent() == null ? "" : element.getComponent();
        switch (kind) {
            case "input", "textarea" -> {
                out.add(caseOf(node, f, "「" + name + "」正常输入与回显验证",
                        "1.已打开「" + surface + "」界面；2.该字段可编辑",
                        "1.点击「" + name + "」输入框；2.输入有效内容（如 自动化测试_001）；"
                                + "3.按 Tab 或点击页面空白处使其失焦；4.再次点击该输入框",
                        "1.输入内容完整回显，没有被截断或乱码；2.失焦后输入值保留，无报错提示；"
                                + "3.再次聚焦时光标与内容正常",
                        "positive", "P1", "P1；等价类-有效值", url));
                out.add(caseOf(node, f, "「" + name + "」必填/空值校验验证",
                        "1.已打开「" + surface + "」界面；2.已知该字段是否必填",
                        "1.不填写「" + name + "」，直接点击界面上的提交/确定按钮；2.再输入 3 个空格后重复提交；"
                                + "3.清空该字段后重复提交",
                        "1.必填字段为空时给出明确的校验提示并定位到「" + name + "」；2.纯空格按空值处理，不通过校验；"
                                + "3.非必填字段为空时按设计放行，无报错弹窗",
                        "negative", "P1", "P1；异常值-空值与纯空格", url));
                out.add(caseOf(node, f, "「" + name + "」长度边界验证",
                        "1.已打开「" + surface + "」界面；2.已明确该字段长度上限（未明确时按 255 字符验证）",
                        "1.在「" + name + "」输入 1 个字符；2.输入达到上限的字符数；3.输入上限 +1 与 1000 个字符",
                        "1.下边界 1 个字符可正常接受；2.上限值可完整保存/展示；3.超过上限时被截断或给出长度提示，"
                                + "不出现 500 或页面卡死",
                        "boundary", "P2", "P2；边界值分析", url));
                out.add(caseOf(node, f, "「" + name + "」特殊字符与注入字符验证",
                        "1.已打开「" + surface + "」界面；2.该字段接受文本输入",
                        "1.在「" + name + "」依次输入 ' \" ; -- % & < > 与 <script>alert(1)</script>；"
                                + "2.保存/提交后查看回显；3.刷新页面再次查看",
                        "1.特殊字符按原样回显或被正确转义；2.页面结构不错乱、不执行脚本；3.刷新后内容一致，无异常报错",
                        "negative", "P2", "P2；异常值-特殊字符", url));
                if (element.getSelector() != null && element.getSelector().contains("textarea")) {
                    out.add(caseOf(node, f, "「" + name + "」多行文本与换行验证",
                            "1.已打开「" + surface + "」界面",
                            "1.在「" + name + "」输入 5 行文本并换行；2.保存后查看展示；3.再次编辑",
                            "1.换行与段落结构被保留；2.展示时无内容丢失；3.再次编辑时内容与换行一致",
                            "positive", "P2", "P2；多行文本", url));
                }
            }
            case "select" -> {
                out.add(caseOf(node, f, "「" + name + "」选项枚举与回显验证",
                        "1.已打开「" + surface + "」界面",
                        "1.展开「" + name + "」下拉框，记录全部可选项；2.选择其中一项；3.再次展开下拉框；"
                                + "4.提交后重新进入界面查看",
                        "1.选项列表与业务字典一致、顺序稳定；2.选择后回显所选文本；3.再次展开时选中项高亮；"
                                + "4.提交后保存值与所选一致",
                        "positive", "P1", "P1；等价类-有效选项", url));
                out.add(caseOf(node, f, "「" + name + "」默认值与空选择验证",
                        "1.已打开「" + surface + "」界面；2.了解该下拉的默认值定义",
                        "1.观察「" + name + "」首次打开时的默认值；2.若允许清空，清空后提交；3.不选择直接提交",
                        "1.默认值符合设计（无默认时显示占位提示）；2.清空后按空值处理；3.必填且未选择时给出提示",
                        "boundary", "P2", "P2；默认值与空选择", url));
            }
            case "check" -> {
                out.add(caseOf(node, f, "「" + name + "」勾选状态切换验证",
                        "1.已打开「" + surface + "」界面",
                        "1.点击「" + name + "」将其勾选；2.再次点击取消勾选；3.提交后查看保存结果",
                        "1.勾选/取消状态即时切换并有视觉反馈；2.提交值与界面状态一致；3.重复切换不出现状态错乱",
                        "positive", "P1", "P1；状态切换", url));
                if (component.contains("单选")) {
                    out.add(caseOf(node, f, "「" + name + "」单选互斥验证",
                            "1.已打开「" + surface + "」界面；2.同组存在多个单选项",
                            "1.选择「" + name + "」；2.再选择同组的其它单选项；3.提交后查看保存值",
                            "1.同组内始终只有一项被选中；2.切换后保存值为最后选择的项；3.不出现两项同时选中",
                            "negative", "P2", "P2；单选互斥", url));
                }
            }
            case "file" -> {
                out.add(caseOf(node, f, "「" + name + "」合法文件上传验证",
                        "1.已打开「" + surface + "」界面；2.准备允许类型、大小合规的测试文件",
                        "1.点击「" + name + "」选择测试文件；2.上传并等待完成；3.查看文件名与上传结果",
                        "1.文件可被选中并显示文件名；2.上传成功并有明确提示；3.列表/界面中出现该附件",
                        "positive", "P1", "P1；文件上传-有效文件", url));
                out.add(caseOf(node, f, "「" + name + "」非法类型与超限文件验证",
                        "1.已打开「" + surface + "」界面；2.准备超限大小与不允许类型的文件",
                        "1.选择不允许的扩展名上传；2.选择超过大小上限的文件上传；3.取消正在进行的上传",
                        "1.不允许的类型被拦截并提示；2.超过大小上限被拒绝，提示明确；3.取消后不残留临时文件或脏记录",
                        "negative", "P2", "P2；文件上传-异常文件", url));
            }
            case "link" -> out.add(caseOf(node, f, "「" + name + "」跳转与返回验证",
                    "1.已打开「" + surface + "」界面",
                    "1.点击「" + name + "」；2.核对跳转后的页面内容；3.使用浏览器返回",
                    "1.跳转到目标页面或打开新窗口；2.目标页内容与链接语义一致；3.返回后原界面状态保持",
                    "positive", "P2", "P2；链接跳转", url));
            case "chart" -> out.add(caseOf(node, f, "「" + name + "」图例交互与展示验证",
                    "1.已打开「" + surface + "」界面；2.图表数据已加载",
                    "1.观察「" + name + "」对应的图例项；2.点击图例切换显示/隐藏；3.悬浮查看提示信息",
                    "1.图例与图表数据一一对应；2.点击后对应数据系列隐藏或显示，图表重绘正确；3.悬浮提示数值与图例一致",
                    "positive", "P2", "P2；图表交互", url));
            case "button" -> out.addAll(buttonCases(node, f, surface, name, url));
            default -> { }
        }
        return out;
    }

    private List<TestCase> buttonCases(TreeNode node, Fields f, String surface, String name, String url) {
        List<TestCase> out = new ArrayList<>();
        if (containsAny(name, "取消", "关闭", "返回")) {
            out.add(caseOf(node, f, "「" + name + "」取消操作不保存验证",
                    "1.已打开「" + surface + "」界面；2.已在界面上填写/修改过数据",
                    "1.在「" + surface + "」中填写测试数据（如 自动化测试_001）；2.点击「" + name + "」；"
                            + "3.重新打开该界面核对数据",
                    "1.界面按设计关闭或返回上一页；2.本次填写的数据未被保存；3.重新打开时字段为初始状态，无残留数据",
                    "positive", "P1", "P1；取消/回滚", url));
            return out;
        }
        if (containsAny(name, "重置", "清空")) {
            out.add(caseOf(node, f, "「" + name + "」重置后恢复初始状态验证",
                    "1.已打开「" + surface + "」界面；2.已在界面上设置过条件或输入内容",
                    "1.设置若干条件/输入内容；2.点击「" + name + "」；3.再次查看界面状态",
                    "1.条件与输入被清空并恢复默认值；2.界面提示与数据回到初始状态；3.无报错且不触发额外请求",
                    "positive", "P2", "P2；重置", url));
            return out;
        }
        out.add(caseOf(node, f, "「" + name + "」点击后的界面反馈验证",
                "1.已打开「" + surface + "」界面；2.已按界面要求填写测试数据（如 自动化测试_001）",
                "1.填写/确认「" + surface + "」中的必填内容；2.点击「" + name + "」；3.观察提示、弹窗与数据变化",
                "1.点击后按设计提交或刷新数据，并给出明确的成功/失败提示；2.页面无 JS 报错与重复弹窗；"
                        + "3.操作结果与界面展示一致（仅使用测试数据）",
                "positive", "P1", "P1；主流程", url));
        out.add(caseOf(node, f, "「" + name + "」重复点击与响应期间状态验证",
                "1.已打开「" + surface + "」界面；2.已按界面要求准备好测试数据",
                "1.快速连续点击「" + name + "」5 次；2.观察响应期间的按钮状态与提示；3.等待结束后核对结果",
                "1.同一操作只生效 1 次，无重复数据或重复弹窗；2.响应期间按钮禁用或显示加载态；3.结束后界面状态与结果一致",
                "negative", "P2", "P2；重复操作与按钮状态", url));
        return out;
    }

    /** 只有在名称里确实出现对应业务词时才补用例，避免给没有查询/分页的界面硬造用例。 */
    private List<TestCase> contextCases(TreeNode node, Fields f, String surface, String pointName,
                                        List<ElementCandidate> fields, String url) {
        List<TestCase> out = new ArrayList<>();
        String evidence = pointName + " " + surface + " "
                + fields.stream().map(ElementCandidate::getName).collect(java.util.stream.Collectors.joining(" "));
        if (containsAny(evidence, "查询", "检索", "搜索", "筛选", "条件", "列表", "分页")) {
            out.add(caseOf(node, f, surface + "条件查询与结果过滤验证",
                    "1.已打开「" + surface + "」界面；2.存在可命中的测试数据",
                    "1.在查询条件中输入合法关键字；2.点击「查询」；3.核对结果列表是否按条件过滤；4.点击「重置」后再查询",
                    "1.结果只包含命中条件的数据；2.重置后条件清空并恢复默认列表；3.查询过程无报错弹窗",
                    "positive", "P1", "P1；等价类-有效等价类", url));
            out.add(caseOf(node, f, surface + "多条件组合查询验证",
                    "1.已打开「" + surface + "」界面；2.存在满足组合条件的测试数据",
                    "1.同时设置两个及以上查询条件；2.点击「查询」；3.去掉其中一个条件后再次查询",
                    "1.多条件之间为「与」关系，结果同时满足所有条件；2.去掉条件后结果集相应扩大；3.分页与总数同步刷新",
                    "positive", "P2", "P2；等价类-条件组合", url));
            out.add(caseOf(node, f, surface + "无匹配结果与非法关键字验证",
                    "1.已打开「" + surface + "」界面",
                    "1.输入不存在的关键字查询；2.输入纯空格查询；3.输入超长关键字（1000 字符）查询",
                    "1.无匹配时展示空列表与「暂无数据」提示，不报错；2.纯空格按无条件处理；3.超长关键字被限制且不超时",
                    "negative", "P2", "P2；异常值-无结果与非法输入", url));
            out.add(caseOf(node, f, surface + "分页边界验证",
                    "1.已打开「" + surface + "」界面；2.结果数据超过 1 页",
                    "1.查看首页与末页；2.跳转到超出总页数的页码；3.切换每页条数后再翻页",
                    "1.首页/末页数据正确；2.越界页码被限制或提示；3.每页条数切换后页码与总数同步刷新",
                    "boundary", "P2", "P2；分页边界", url));
        }
        if (containsAny(evidence, "日期", "时间", "时间段")) {
            out.add(caseOf(node, f, surface + "日期区间与格式验证",
                    "1.已打开「" + surface + "」界面；2.界面含开始/结束时间类条件",
                    "1.选择开始时间晚于结束时间；2.输入非法日期（2024-13-45）；3.清空时间后再查询",
                    "1.区间颠倒时给出提示或自动纠正；2.非法日期被拦截；3.清空后按无该条件处理",
                    "negative", "P2", "P2；日期边界与格式", url));
        }
        return out;
    }

    private static boolean containsAny(String text, String... keywords) {
        if (text == null) return false;
        for (String keyword : keywords) if (text.contains(keyword)) return true;
        return false;
    }

    private TestCase caseOf(TreeNode node, Fields f, String title, String pre, String steps, String expect,
                            String category, String priority, String remark, String url) {
        TestCase testCase = new TestCase(node.getProjectId(), node.getSystemId(), node.getId(), title);
        testCase.onCreateForStore();
        testCase.setModuleName(f.moduleCat());
        testCase.setPriority(priority);
        testCase.setCategory(category);
        testCase.setPreconditions(pre);
        testCase.setSteps(steps);
        testCase.setExpectedResult(expect);
        testCase.setStatus("DRAFT");
        testCase.setDesignRequirement(f.designRequirement());
        testCase.setTestTrack(f.testTrack());
        testCase.setRemark(remark.length() > 1900 ? remark.substring(0, 1900) : remark);
        // 这里不知道点击后调的是哪个接口：留空比「GET 页面地址」这种假参数诚实，有样本时由 applyRuleCalls 补真实调用。
        testCase.setParamsText("");
        testCase.setMethod("GET");
        testCase.setUrl(url);
        testCase.setL1(f.titleL1());
        testCase.setL2(f.titleL2());
        return testCase;
    }

    private static final int AI_BATCH_SIZE = 6;
    /** 带截图时单次调用更慢（视觉模型 90 秒超时），批小一点，免得整批超时一起失败。 */
    private static final int AI_VISION_BATCH_SIZE = 3;

    /**
     * 先按「截图」分组，再在组内分批请求：一次调用只能带一张图，
     * 混了别的页面的功能点进来，模型就会照着错的界面写。
     * 某批失败只回退这一批，不影响其它批次。
     */
    private AiResult generateWithAi(List<TreeNode> points, Map<String, Fields> fieldsByPoint,
                                          Map<String, List<ElementCandidate>> elementsByPoint,
                                          Map<String, ElementCandidate> systemElements,
                                          Options options, String sysName,
                                          Map<String, String> shotByPoint, Map<String, byte[]> imageCache,
                                          Map<String, List<com.yuceyang.uitest.browser.NetworkExchange>> callsByPoint) {
        Map<String, Integer> targetByPoint = new HashMap<>();
        for (TreeNode point : points) {
            targetByPoint.put(point.getId(), randomTarget(options));
        }
        Map<String, List<TreeNode>> groups = new LinkedHashMap<>();
        for (TreeNode point : points) {
            String shot = shotByPoint.get(point.getId());
            groups.computeIfAbsent(shot == null ? "" : shot, key -> new ArrayList<>()).add(point);
        }
        List<TestCase> result = new ArrayList<>();
        int toppedUp = 0;
        List<String> shortfalls = new ArrayList<>();
        for (Map.Entry<String, List<TreeNode>> group : groups.entrySet()) {
            byte[] pageImage = group.getKey().isEmpty() ? null : imageCache.get(group.getKey());
            int batchSize = pageImage == null ? AI_BATCH_SIZE : AI_VISION_BATCH_SIZE;
            List<TreeNode> groupPoints = group.getValue();
            for (int from = 0; from < groupPoints.size(); from += batchSize) {
                List<TreeNode> batch = new ArrayList<>(groupPoints.subList(from,
                        Math.min(groupPoints.size(), from + batchSize)));
                try {
                    BatchOutcome outcome = generateWithAiBatch(batch, fieldsByPoint, elementsByPoint,
                            systemElements, options, sysName, targetByPoint, pageImage, callsByPoint);
                    result.addAll(outcome.cases());
                    toppedUp += outcome.toppedUp();
                    shortfalls.addAll(outcome.shortfalls());
                } catch (RuntimeException e) {
                    log.warn("AI 用例生成失败（{} 个功能点一批），该批回落到规则生成：{}",
                            batch.size(), e.getMessage());
                    for (TreeNode point : batch) {
                        List<TestCase> built = new ArrayList<>(buildRules(point, fieldsByPoint.get(point.getId()),
                                elementsByPoint.getOrDefault(point.getId(), List.of()), systemElements,
                                options, sysName));
                        applyRuleCalls(built, callsByPoint.getOrDefault(point.getId(), List.of()));
                        result.addAll(built);
                    }
                }
            }
        }
        if (result.isEmpty()) throw new IllegalStateException("AI 未返回符合结构的用例");
        return new AiResult(result, toppedUp, shortfalls);
    }

    /** AI 结果 + 其中多少条按「下限」用规则补齐 + 补完仍不足下限的功能点。 */
    private record AiResult(List<TestCase> cases, int toppedUp, List<String> shortfalls) {}
    private record BatchOutcome(List<TestCase> cases, int toppedUp, List<String> shortfalls) {}

    private BatchOutcome generateWithAiBatch(List<TreeNode> points, Map<String, Fields> fieldsByPoint,
                                               Map<String, List<ElementCandidate>> elementsByPoint,
                                               Map<String, ElementCandidate> systemElements,
                                               Options options, String sysName, Map<String, Integer> targetByPoint,
                                               byte[] pageImage,
                                               Map<String, List<com.yuceyang.uitest.browser.NetworkExchange>> callsByPoint) {
        List<Map<String, Object>> compact = new ArrayList<>();
        for (TreeNode point : points) {
            Fields f = fieldsByPoint.get(point.getId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", point.getId());
            item.put("name", f.pointName());
            item.put("target", targetByPoint.getOrDefault(point.getId(), options.lower()));
            item.put("path", f.deepPath());
            item.put("method", "GET");
            item.put("url", options.url() == null ? "" : options.url());
            item.put("summary", f.designRequirement());
            // 只把真实识别到的元素交给模型，模型据此决定测什么，不再靠猜测。
            List<Map<String, Object>> elements = new ArrayList<>();
            for (ElementCandidate element : elementsByPoint.getOrDefault(point.getId(), List.of())) {
                if (element == null || blank(element.getName())) continue;
                Map<String, Object> el = new LinkedHashMap<>();
                el.put("name", element.getName());
                el.put("kind", blank(element.getKind()) ? "other" : element.getKind());
                el.put("component", element.getComponent() == null ? "" : element.getComponent());
                elements.add(el);
                if (elements.size() >= 12) break;
            }
            item.put("elements", elements);
            // 真实接口样本：method/url/params 全部来自浏览器实际发出的请求，模型只许照抄，不许编。
            List<Map<String, Object>> requests = new ArrayList<>();
            for (com.yuceyang.uitest.browser.NetworkExchange call
                    : callsByPoint.getOrDefault(point.getId(), List.of())) {
                if (requests.size() >= 6) break;
                Map<String, Object> request = new LinkedHashMap<>();
                request.put("method", call.method());
                request.put("url", call.url());
                String params = call.params();
                if (!params.isBlank()) request.put("params", params);
                requests.add(request);
            }
            item.put("requests", requests);
            item.put("tags", List.of(f.moduleCat()));
            compact.add(item);
        }
        String userReq = blank(options.extraRequirement())
                ? "无额外要求，按界面元素本身设计。" : options.extraRequirement().trim();
        String vision = pageImage != null
                ? """
                  【界面截图】本次请求附带一张页面截图，是这些功能点所在界面的真实画面。
                  截图优先于一切文字猜测：先用截图认界面（是弹窗、表单还是列表），再把截图里真实可见的
                  控件名称、按钮文案、提示语原样写进步骤与预期（简体中文名字照抄，英文/占位符照抄）。
                  截图里看不到的控件一律不许写；elements 清单与截图冲突时，以截图为准。
                  某个功能点若在截图里找不到对应区域，就只写一条「界面加载与入口可达」类用例，不要凭功能点名字编造字段。
                  """
                : "【界面截图】本次没有截图，只能严格按 elements 清单与功能点名设计；elements 为空的功能点只写一条「界面加载与入口可达」用例，不得编造任何字段名。";
        String prompt;
        try {
            prompt = """
                    请为以下功能点设计界面测试用例。

                    【设计依据】每个功能点的 elements 是页面上真实识别到的元素（name=界面上的名称，kind=控件类型）。
                    只能围绕这些元素、功能点名称以及下方截图设计用例；没有依据的检索框、查询按钮、分页、列表、导出等控件
                    一律不得出现在标题、步骤或预期里。
                    %s
                    【输入类字段】input/textarea 每个字段至少 1 条用例（正常输入、必填为空、长度边界、特殊字符按需取），
                    标题与步骤必须写出该字段的名称。
                    【按钮】写清点击前后的界面变化、提示文案与数据结果；取消/关闭类按钮要验证「不保存」。
                    【下拉/勾选】验证选项枚举、选中回显、默认值与无效选择；单选验证互斥。
                    【每个功能点】按 target 条生成，条数不必相同。名额先按「覆盖不同元素」分配：
                    elements 里每个输入类字段、每个独立按钮各占 1 条，再给同一元素补边界/异常；不要用一个字段的多条用例占满名额。
                    【需要包含】%s
                    【鉴权用例】%s
                    【接口请求】每个功能点的 requests 是浏览器里真实抓到的接口调用：method、url 来自实际流量，
                    params 已按「参数名=值」拆好，值也是抓到的真实值。
                    「携带参数 / 测试数据」(data) 只能照抄其中与这次操作对应的那一条：一行一个「参数名=值」，
                    query 参数与表单/JSON 字段都要拆开写；method、url 也取自这条请求。
                    requests 为空、或都对不上这次操作时，data 写「未捕获到该操作的接口请求」，method 写 GET、url 写页面地址。
                    绝不把界面状态（例如「已选项：default」「当前页：1」）或臆造的值写进 data；你自己设计的测试输入
                    写在 steps 里，不要混进 data。
                    【测试数据】测试输入用明确的值（如 自动化测试_001），不得要求删除数据库或修改配置；接口参数字段名一律来自 requests。
                    【语言】中文。步骤与预期里出现的每个控件名，都必须来自该功能点的 elements 或截图，二选一，不得自创。
                    【额外人工要求（优先级最高）】
                    %s
                    额外要求点名了某个字段或操作时，必须至少有一条用例的标题与步骤写的就是它；即使它不在 elements 清单里，
                    也只在截图里确实存在时才写，并在 ai_rationale 里注明「按额外人工要求设计」——为此可以压缩同一字段的重复用例。
                    额外要求没点名时，才按 elements 与 target 自行分配。

                    【功能点列表】
                    %s

                    请输出严格 JSON。
                    """.formatted(
                    vision,
                    options.includeNegative() ? "异常/边界" : "主要正向",
                    options.includeAuth() ? "需要（未登录跳转/无权限提示即可，不改数据）" : "不需要",
                    userReq,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(compact));
        } catch (Exception e) {
            throw new IllegalStateException("构造 AI 提示词失败：" + e.getMessage(), e);
        }
        String text;
        if (pageImage == null) {
            text = aiClient.chat(SYSTEM_PROMPT, prompt);
        } else {
            try {
                text = aiClient.chatVision(SYSTEM_PROMPT, prompt, pageImage, "image/png");
            } catch (RuntimeException e) {
                // 模型不支持图片或图片过大时，退回纯文本，至少还有元素清单可依。
                log.warn("带图生成失败，改用文本生成：{}", e.getMessage());
                text = aiClient.chat(SYSTEM_PROMPT, prompt);
            }
        }
        JsonNode root;
        try {
            root = mapper.readTree(stripFences(text));
        } catch (Exception e) {
            throw new IllegalStateException("AI 输出 JSON 解析失败：" + e.getMessage(), e);
        }
        JsonNode casesNode = root.path("cases");
        Map<String, TreeNode> byId = new HashMap<>();
        points.forEach(p -> byId.put(p.getId(), p));
        List<TestCase> result = new ArrayList<>();
        Map<String, Integer> perPoint = new HashMap<>();
        for (JsonNode item : casesNode) {
            String pointId = item.path("point_id").asText("");
            TreeNode point = byId.get(pointId);
            if (point == null) continue;
            int count = perPoint.getOrDefault(pointId, 0);
            if (count >= targetByPoint.getOrDefault(pointId, options.upper())) continue;
            perPoint.put(pointId, count + 1);
            Fields f = fieldsByPoint.get(pointId);
            String title = item.path("title").asText("").trim();
            if (title.isEmpty()) continue;
            TestCase testCase = new TestCase(point.getProjectId(), point.getSystemId(), pointId, title);
            testCase.onCreateForStore();
            testCase.setPreconditions(text(item, "preconditions"));
            testCase.setSteps(text(item, "steps"));
            testCase.setExpectedResult(text(item, "expected"));
            testCase.setModuleName(firstNonBlank(text(item, "module"), f.moduleCat()));
            testCase.setPriority(firstNonBlank(text(item, "priority"), "P2"));
            testCase.setCategory(firstNonBlank(text(item, "category"), "positive"));
            testCase.setParamsText(nullSafe(text(item, "data")));
            testCase.setDesignRequirement(f.designRequirement());
            testCase.setTestTrack(f.testTrack());
            String method = firstNonBlank(text(item, "method"), "GET");
            String url = text(item, "url");
            String auth = text(item, "auth_notes");
            String rationale = text(item, "ai_rationale");
            String remark = join(" · ", List.of(method, url, auth, rationale));
            testCase.setRemark(remark.length() > 1900 ? remark.substring(0, 1900) : remark);
            testCase.setMethod(method);
            testCase.setUrl(url);
            testCase.setL1(f.titleL1());
            testCase.setL2(f.titleL2());
            testCase.setStatus("DRAFT");
            result.add(testCase);
        }
        if (!blank(options.extraRequirement())) {
            result.addAll(extraRequirementCases(points, fieldsByPoint, elementsByPoint, systemElements, options, result));
        }
        TopUp topUp = topUpToMinimum(result, points, targetByPoint, fieldsByPoint, elementsByPoint,
                systemElements, options, callsByPoint);
        if (result.isEmpty()) throw new IllegalStateException("AI 未返回符合结构的用例");
        return new BatchOutcome(result, topUp.added(), topUp.shortfalls());
    }

    /**
     * AI 条数不够时按规则补齐。
     * <p>「下限~上限」是用户对每个功能点的承诺：把下限设成 4，就不该因为模型只写了 1 条而只入库 1 条。
     * 模型给的内容优先保留，缺口用<b>同一套规则候选</b>补（元素驱动，不凭空造字段），补到这次的 target 为止；
     * 候选本身不够时（功能点既没挂元素、截图里也没有对应区域）就停手——宁可少，不编造。</p>
     *
     * @return 补了几条；哪些功能点补完仍不足下限（形如「用户名 1/4」）
     */
    record TopUp(int added, List<String> shortfalls) {}

    TopUp topUpToMinimum(List<TestCase> result, List<TreeNode> points,
                         Map<String, Integer> targetByPoint,
                         Map<String, Fields> fieldsByPoint,
                         Map<String, List<ElementCandidate>> elementsByPoint,
                         Map<String, ElementCandidate> systemElements, Options options,
                         Map<String, List<com.yuceyang.uitest.browser.NetworkExchange>> callsByPoint) {
        Map<String, Integer> haveByPoint = new HashMap<>();
        for (TestCase testCase : result) {
            haveByPoint.merge(testCase.getPointId(), 1, Integer::sum);
        }
        int added = 0;
        List<String> shortfalls = new ArrayList<>();
        for (TreeNode point : points) {
            int have = haveByPoint.getOrDefault(point.getId(), 0);
            int want = Math.min(options.upper(), Math.max(options.lower(),
                    targetByPoint.getOrDefault(point.getId(), options.lower())));
            if (have >= want) continue;
            Set<String> titles = new HashSet<>();
            for (TestCase testCase : result) {
                if (point.getId().equals(testCase.getPointId())) titles.add(titleKey(testCase.getTitle()));
            }
            RuleSet rules = ruleSet(point, fieldsByPoint.get(point.getId()),
                    elementsByPoint.getOrDefault(point.getId(), List.of()), systemElements, options);
            List<TestCase> built = new ArrayList<>();
            for (TestCase candidate : rules.ordered()) {
                if (have + built.size() >= want) break;
                // 已有 AI 用例时不再补「界面加载」：它的作用就是证明页面能进去，AI 那条已经证明了
                if (candidate == rules.pageLoad() && have > 0) continue;
                if (!titles.add(titleKey(candidate.getTitle()))) continue;
                built.add(candidate);
            }
            if (!built.isEmpty()) {
                applyRuleCalls(built, callsByPoint.getOrDefault(point.getId(), List.of()));
                for (TestCase testCase : built) {
                    testCase.setRemark(withNote(testCase.getRemark(),
                            "规则补齐（AI 只给了 " + have + " 条，按下限补到 " + want + " 条）"));
                }
                result.addAll(built);
                haveByPoint.put(point.getId(), have + built.size());
                added += built.size();
            }
            int got = haveByPoint.getOrDefault(point.getId(), 0);
            if (got < options.lower()) {
                shortfalls.add(point.getName() + " " + got + "/" + options.lower());
            }
        }
        if (added > 0) {
            log.info("[用例生成] AI 条数不足，按规则补齐 {} 条（下限 {}），仍不足的：{}",
                    added, options.lower(), shortfalls);
        }
        return new TopUp(added, shortfalls);
    }

    private static String titleKey(String title) {
        return title == null ? "" : title.replaceAll("[\\s\\p{Punct}，。、；：「」（）()【】\\[\\]！？]", "");
    }

    private static String withNote(String remark, String note) {
        if (remark == null || remark.isBlank()) return note;
        String merged = remark + " · " + note;
        return merged.length() > 1900 ? merged.substring(0, 1900) : merged;
    }

    /**
     * 额外人工要求的兜底：要求里点名的元素在 AI 结果里一条用例都没提到时，按规则补一条。
     * 用户写了额外要求却看不到对应用例，比多生成一条用例糟糕得多——所以这里不静默丢弃，
     * 只在已经达到 maxPerPoint 上限时才停手（并写日志说明）。
     */
    private List<TestCase> extraRequirementCases(List<TreeNode> points, Map<String, Fields> fieldsByPoint,
                                                 Map<String, List<ElementCandidate>> elementsByPoint,
                                                 Map<String, ElementCandidate> systemElements,
                                                 Options options, List<TestCase> produced) {
        List<TestCase> extra = new ArrayList<>();
        for (TreeNode point : points) {
            Fields f = fieldsByPoint.get(point.getId());
            if (f == null) continue;
            String surface = blank(f.surface()) ? f.pointName() : f.surface();
            String url = options.url() == null ? "" : options.url();
            long count = produced.stream().filter(c -> point.getId().equals(c.getPointId())).count();
            List<ElementCandidate> candidates = new ArrayList<>(elementsByPoint.getOrDefault(point.getId(), List.of()));
            // 只在功能点没有任何元素可依据时，才动用全局元素（手建功能点）；否则会把这个字段串到同页所有功能点上
            if (candidates.isEmpty()) {
                for (ElementCandidate element : systemElements.values()) {
                    if (namedInExtra(element, options.extraRequirement())) candidates.add(element);
                }
            }
            for (ElementCandidate element : candidates) {
                if (!namedInExtra(element, options.extraRequirement())) continue;
                String core = elementCore(element.getName());
                boolean covered = produced.stream()
                        .filter(c -> point.getId().equals(c.getPointId()))
                        .anyMatch(c -> mentions(c, core));
                if (covered) continue;
                if (count >= options.upper()) {
                    log.info("额外要求点名的「{}」未进入用例：功能点「{}」已达条数上限 {}",
                            element.getName(), f.pointName(), options.upper());
                    break;
                }
                List<TestCase> built = elementCases(point, f, surface, element, url);
                if (built.isEmpty()) continue;
                extra.add(built.get(0));
                count++;
            }
        }
        return extra;
    }

    private void bindSequenceAndScreenshot(List<TestCase> cases, String screenshotId, int startNo) {
        bindSequenceAndScreenshot(cases, null, screenshotId, startNo);
    }

    /** 同一批用例可能跨页面：每条用例挂到它所属功能点自己那张截图上。 */
    private void bindSequenceAndScreenshot(List<TestCase> cases, Map<String, String> shotByPoint,
                                           String screenshotId, int startNo) {
        int seq = Math.max(1, startNo);
        for (TestCase testCase : cases) {
            testCase.setCaseNo(com.yuceyang.uitest.testcase.CaseNumbers.format(seq++));
            String shot = shotByPoint == null || testCase.getPointId() == null
                    ? null : shotByPoint.get(testCase.getPointId());
            if (shot == null || shot.isBlank()) shot = screenshotId;
            if (shot != null && !shot.isBlank()) testCase.setScreenshotId(shot);
        }
    }

    /**
     * 新用例的起始序号：只在**没被删的**用例里取最大值 + 1。
     * 以前把已删用例也算进来，删得越多号跳得越远，最后「用例 1302 条、最大编号 GN-01876」对不上；
     * 删掉的号本来就是空的，拿来接着用不会撞车（应用没有恢复已删用例的入口）。
     */
    private static int nextCaseSequence(WorkspaceSnapshot workspace) {
        int max = 0;
        for (TestCase testCase : workspace.testCases) {
            if (testCase.getDeletedAt() != null) continue;
            String no = testCase.getCaseNo();
            if (no == null || no.isBlank()) continue;
            String digits = no.replaceAll("^\\D+0*", "");
            try {
                max = Math.max(max, Integer.parseInt(digits));
            } catch (NumberFormatException ignored) { }
        }
        return max + 1;
    }

    private Fields resolveFields(TreeNode node, Map<String, TreeNode> byId, String sysName) {
        List<TreeNode> chain = new ArrayList<>();
        TreeNode current = node;
        int guard = 0;
        while (current != null && guard++ < 30) {
            chain.add(current);
            current = current.getParentId() == null ? null : byId.get(current.getParentId());
        }
        java.util.Collections.reverse(chain);
        String module = null;
        List<String> dirs = new ArrayList<>();
        String pointName = node.getName() == null ? "功能点" : node.getName();
        // surface = 离功能点最近的上一级名称（通常是弹窗/页面名，如「创建项目」），用例标题与步骤都用它。
        String surface = null;
        for (TreeNode item : chain) {
            String name = item.getName() == null ? "" : item.getName().trim();
            if ("/".equals(name)) name = "";
            if (item.getNodeType() == NodeType.MODULE) {
                if (!name.isEmpty()) { module = name; surface = name; }
            } else if (item.getNodeType() == NodeType.TITLE1 || item.getNodeType() == NodeType.TITLE2
                    || item.getNodeType() == NodeType.TITLE3) {
                if (!name.isEmpty()) { dirs.add(name); surface = name; }
            } else if (item.getNodeType() == NodeType.POINT) {
                if (!name.isEmpty()) pointName = name;
            }
        }
        String moduleCat = module == null || module.isBlank() ? sysName : module;
        String l1 = dirs.isEmpty() ? "/" : dirs.get(0);
        String l2 = dirs.size() >= 2 ? String.join("-", dirs.subList(1, dirs.size())) : "/";
        String track = "/".equals(l1) ? moduleCat + "-" + pointName : moduleCat + "-" + l1;
        List<String> parts = new ArrayList<>();
        parts.add(moduleCat);
        if (!"/".equals(l1)) parts.add(l1);
        if (!"/".equals(l2)) parts.add(l2);
        parts.add(pointName);
        String deepPath = String.join("-", parts);
        String design = "通过界面操作对「" + pointName + "」进行交互与展示验证";
        return new Fields(node.getId(), pointName, moduleCat, l1, l2, deepPath, track, design,
                surface == null ? pointName : surface);
    }

    /**
     * 选哪张截图给视觉模型看。
     * <ol>
     *   <li>先找「功能点元素所属发现会话」的截图——同一会话的截图才是这些元素所在的界面；</li>
     *   <li>没有再用调用方指定的那张（通常是生成前刚抓的当前页）；</li>
     *   <li>最后才退到系统最新的 READY 截图。</li>
     * </ol>
     * 顺序不能反：用户在 A 页点了「生成预览」，树里选中的却是 B 页的功能点时，
     * 直接拿去 A 页截图，模型就会照着 A 页给 B 页写用例——正是「内容跟页面完全不符」。
     */
    private String resolveScreenshotId(WorkspaceSnapshot workspace, String systemId, String preferred,
                                       List<ElementCandidate> pointElements) {
        java.util.Set<String> sessions = new java.util.HashSet<>();
        for (ElementCandidate element : pointElements) {
            if (element != null && element.getSessionId() != null) sessions.add(element.getSessionId());
        }
        Comparator<Screenshot> newest = Comparator.comparing(Screenshot::getCreatedAt,
                Comparator.nullsFirst(Comparator.naturalOrder()));
        List<Screenshot> candidates = workspace.screenshots.stream()
                .filter(s -> s.getDeletedAt() == null && systemId.equals(s.getSystemId()))
                .filter(s -> s.getStatus() == ScreenshotStatus.READY && !s.isQuarantined())
                .toList();
        String matched = candidates.stream()
                .filter(s -> sessions.contains(s.getSessionId()))
                .max(newest).map(Screenshot::getId).orElse(null);
        if (matched != null) return matched;
        if (preferred != null && !preferred.isBlank()) return preferred;
        return candidates.stream().max(newest).map(Screenshot::getId).orElse(null);
    }

    /**
     * 取出截图的原始字节，交给视觉模型看实际界面。
     * 取不到（没有截图、文件丢了、读失败）就返回 null，生成退回纯文本模式，不阻塞。
     */
    private byte[] loadScreenshotBytes(WorkspaceSnapshot workspace, String screenshotId) {
        if (screenshotId == null || screenshotId.isBlank() || screenshotFileStore == null) return null;
        Screenshot screenshot = workspace.screenshots.stream()
                .filter(s -> screenshotId.equals(s.getId())).findFirst().orElse(null);
        if (screenshot == null) return null;
        java.nio.file.Path path = screenshotFileStore.resolve(screenshot.getRelativePath());
        if (path == null) {
            log.warn("用例生成：截图 {} 的文件不存在，按纯文本生成", screenshot.getRelativePath());
            return null;
        }
        try {
            return java.nio.file.Files.readAllBytes(path);
        } catch (java.io.IOException e) {
            log.warn("用例生成：读取截图失败，按纯文本生成：{}", e.getMessage());
            return null;
        }
    }

    private static String stripFences(String text) {
        String t = text == null ? "" : text.trim();
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start >= 0 && end > start) t = t.substring(start, end + 1);
        return t;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String nullSafe(String value) { return value == null ? "" : value; }
    private static String firstNonBlank(String a, String b) { return blank(a) ? nullSafe(b) : a; }
    private static String join(String sep, List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (blank(part)) continue;
            if (sb.length() > 0) sb.append(sep);
            sb.append(part.trim());
        }
        return sb.toString();
    }
}
