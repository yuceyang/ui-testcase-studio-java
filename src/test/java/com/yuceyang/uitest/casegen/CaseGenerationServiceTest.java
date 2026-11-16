package com.yuceyang.uitest.casegen;

import com.yuceyang.uitest.ai.AiClient;
import com.yuceyang.uitest.ai.AiSettings;
import com.yuceyang.uitest.storage.WorkspaceSnapshot;
import com.yuceyang.uitest.system.SystemUnderTest;
import com.yuceyang.uitest.testcase.TestCase;
import com.yuceyang.uitest.tree.NodeType;
import com.yuceyang.uitest.tree.TreeNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CaseGenerationServiceTest {
    private final CaseGenerationService service =
            new CaseGenerationService(new AiClient(new AiSettings("", "", "")), null);

    @Test
    void ruleGenerationProducesReadOnlyCasesWithContinuousNumbering() {
        WorkspaceSnapshot workspace = workspace();
        TestCase existing = new TestCase("p1", "s1", "n3", "历史用例");
        existing.setCaseNo("GN-00007");
        workspace.testCases.add(existing);

        CaseGenerationService.Result result = service.generate(workspace, "s1", List.of("n3"),
                new CaseGenerationService.Options(true, true, 3, 3, "", null, "http://demo/order/list"));

        assertThat(result.aiUsed()).isFalse();
        // 固定 3 条：页面加载 + 鉴权 + 1 条随机测试设计项。
        assertThat(result.cases()).hasSize(3);
        assertThat(result.cases().getFirst().getCaseNo()).isEqualTo("GN-00008");
        assertThat(result.cases().getFirst().getTestTrack()).isEqualTo("订单-查询");
        assertThat(result.cases().getFirst().getModuleName()).isEqualTo("订单");
        assertThat(result.cases()).extracting(TestCase::getCategory).first()
                .isEqualTo("positive");
        assertThat(result.cases().get(1).getCategory()).isEqualTo("auth");
        assertThat(result.cases()).extracting(TestCase::getCaseNo)
                .containsExactly("GN-00008", "GN-00009", "GN-00010");
        assertThat(result.cases().getFirst().getSteps()).doesNotContain("删除", "新增", "修改");
    }

    @Test
    void randomCountStaysWithinConfiguredRange() {
        WorkspaceSnapshot workspace = workspace();
        for (int round = 0; round < 20; round++) {
            CaseGenerationService.Result result = service.generate(workspace, "s1", List.of("n3"),
                    new CaseGenerationService.Options(true, false, 3, 8, "", null, "http://demo/list"));
            assertThat(result.cases()).hasSizeBetween(3, 8);
        }
    }

    @Test
    void extraRequirementNamingAFieldWinsCasesEvenWhenTheFieldIsNotBoundToThePoint() {
        WorkspaceSnapshot workspace = workspace();
        // 功能点只挂了「请输入用户名」，密码输入框是同一页面里还没挂到树上的元素。
        TreeNode point = workspace.nodes.stream().filter(n -> "n3".equals(n.getId())).findFirst().orElseThrow();
        point.setElementId("e1");
        workspace.elements.add(element("e1", "请输入用户名", "input"));
        workspace.elements.add(element("e2", "请输入密码", "input"));
        workspace.elements.add(element("e3", "登录", "button"));

        CaseGenerationService.Result result = service.generate(workspace, "s1", List.of("n3"),
                new CaseGenerationService.Options(true, false, 3, 3, "再添加密码输入框的测试用例", null,
                        "http://demo/login"));

        assertThat(result.cases()).hasSize(3);
        assertThat(result.cases()).anySatisfy(c -> assertThat(
                c.getTitle() + c.getSteps() + c.getExpectedResult()).contains("密码"));
        // 页面加载用例仍然排第一，额外要求的用例挤进剩余名额
        assertThat(result.cases().getFirst().getTitle()).contains("界面加载");
    }

    @Test
    void pointWithoutExtraRequirementKeepsNormalOrder() {
        WorkspaceSnapshot workspace = workspace();
        workspace.nodes.stream().filter(n -> "n3".equals(n.getId())).findFirst().orElseThrow()
                .setElementId("e1");
        workspace.elements.add(element("e1", "请输入用户名", "input"));

        CaseGenerationService.Result result = service.generate(workspace, "s1", List.of("n3"),
                new CaseGenerationService.Options(true, false, 3, 3, "", null, "http://demo/login"));

        assertThat(result.cases()).hasSize(3);
        assertThat(result.cases().getFirst().getTitle()).contains("界面加载");
        assertThat(result.cases()).noneSatisfy(c -> assertThat(
                c.getTitle() + c.getSteps()).contains("密码"));
    }

    @Test
    void unownedElementsOnTheSamePageDoNotLeakIntoUnrelatedPoints() {
        WorkspaceSnapshot workspace = workspace();
        workspace.nodes.stream().filter(n -> "n3".equals(n.getId())).findFirst().orElseThrow()
                .setElementId("e3");
        workspace.elements.add(element("e3", "查询", "button"));
        workspace.elements.add(element("e2", "请输入密码", "input"));

        CaseGenerationService.Result result = service.generate(workspace, "s1", List.of("n3"),
                new CaseGenerationService.Options(true, false, 3, 3, "", null, "http://demo/login"));

        assertThat(result.cases()).hasSize(3);
        assertThat(result.cases()).noneSatisfy(c -> assertThat(
                c.getTitle() + c.getSteps()).contains("密码"));
    }

    @Test
    void pointWithoutElementsNeverBorrowsFieldsFromOtherPages() {
        WorkspaceSnapshot workspace = workspace();
        // 功能点一个元素都没挂，系统里却有别的页面（项目表单）留下的元素。
        workspace.elements.add(element("e1", "开始日期时间", "input"));
        workspace.elements.add(element("e2", "所属用户输入框", "input"));
        workspace.elements.add(element("e3", "项目描述输入框", "input"));
        workspace.elements.add(element("e4", "请输入密码", "input"));

        CaseGenerationService.Result result = service.generate(workspace, "s1", List.of("n3"),
                new CaseGenerationService.Options(true, false, 3, 3, "", null, "http://demo/dialog"));

        assertThat(result.cases()).isNotEmpty();
        assertThat(result.cases()).noneSatisfy(c -> assertThat(
                c.getTitle() + c.getSteps() + c.getExpectedResult())
                .contains("开始日期时间", "所属用户", "项目描述", "密码"));
    }

    @Test
    void realApiCallsFromTheBrowserAreHandedToTheModelInsteadOfInventedParams() {
        WorkspaceSnapshot workspace = workspace();
        workspace.nodes.stream().filter(n -> "n3".equals(n.getId())).findFirst().orElseThrow()
                .setElementId("e1");
        workspace.elements.add(element("e1", "请输入用户名", "input"));

        com.yuceyang.uitest.browser.NetworkLog networkLog = new com.yuceyang.uitest.browser.NetworkLog();
        networkLog.record(new com.yuceyang.uitest.browser.NetworkExchange("sess-1", "GET",
                "http://demo/api/users?page=1&pageSize=20", "xhr", 200, null, 0L));
        StubAi ai = new StubAi();
        CaseGenerationService aiService = new CaseGenerationService(ai, null, networkLog);

        CaseGenerationService.Result result = aiService.generate(workspace, "s1", List.of("n3"),
                new CaseGenerationService.Options(false, false, 1, 1, "", null, "http://demo/login"));

        // 提示词里必须带上真实的请求与拆好的参数，模型才有东西可抄
        assertThat(ai.prompt).contains("http://demo/api/users", "pageSize=20", "page=1");
        assertThat(result.cases()).isNotEmpty();
        assertThat(result.cases().getFirst().getParamsText()).contains("pageSize=20");
        assertThat(result.note()).contains("真实接口样本");
    }

    @Test
    void liveSessionCallsFillInWhenThePointHasNoCaptureOfItsOwn() {
        WorkspaceSnapshot workspace = workspace();
        workspace.nodes.stream().filter(n -> "n3".equals(n.getId())).findFirst().orElseThrow()
                .setElementId("e1");
        // 元素来自更早的会话（没抓到请求），本次浏览的 live 会话才有接口样本
        workspace.elements.add(element("e1", "请输入用户名", "input"));

        com.yuceyang.uitest.browser.NetworkLog networkLog = new com.yuceyang.uitest.browser.NetworkLog();
        networkLog.record(new com.yuceyang.uitest.browser.NetworkExchange("sess-live", "POST",
                "http://demo/api/projects", "fetch", 200,
                "{\"projectCode\":\"ds-001\",\"workerGroup\":\"default\"}", 0L));
        StubAi ai = new StubAi();
        CaseGenerationService aiService = new CaseGenerationService(ai, null, networkLog);

        CaseGenerationService.Result result = aiService.generate(workspace, "s1", List.of("n3"),
                new CaseGenerationService.Options(false, false, 1, 1, "", null,
                        "http://demo/login", "sess-live"));

        assertThat(ai.prompt).contains("/api/projects", "projectCode=ds-001", "workerGroup=default");
        assertThat(result.note()).contains("真实接口样本");
    }

    @Test
    void withoutCapturedCallsNothingIsFabricatedIntoParams() {
        WorkspaceSnapshot workspace = workspace();
        workspace.nodes.stream().filter(n -> "n3".equals(n.getId())).findFirst().orElseThrow()
                .setElementId("e1");
        workspace.elements.add(element("e1", "请输入用户名", "input"));

        CaseGenerationService.Result result = service.generate(workspace, "s1", List.of("n3"),
                new CaseGenerationService.Options(false, false, 2, 2, "", null, "http://demo/login"));

        assertThat(result.cases()).allSatisfy(c -> assertThat(c.getParamsText())
                .satisfiesAnyOf(
                        text -> assertThat(text).isNull(),
                        text -> assertThat(text).isEmpty()));
    }

    /** 只回固定 JSON，并把收到的提示词留下来，用于断言「真实接口样本确实进了提示词」。 */
    private static final class StubAi extends AiClient {
        String prompt;
        StubAi() { super(new AiSettings("http://stub", "stub-model", "stub-key")); }
        @Override public boolean available() { return true; }
        @Override public String model() { return "stub"; }
        @Override public String chat(String systemPrompt, String userPrompt) {
            prompt = userPrompt;
            return "{\"cases\":[{\"point_id\":\"n3\",\"title\":\"按真实接口参数查询\","
                    + "\"steps\":\"1. 输入用户名 2. 点击查询\",\"expected\":\"按 pageSize=20 返回\","
                    + "\"data\":\"page=1\\npageSize=20\",\"method\":\"GET\","
                    + "\"url\":\"http://demo/api/users?page=1&pageSize=20\"}]}";
        }
    }

    private static com.yuceyang.uitest.element.ElementCandidate element(String id, String name, String kind) {
        com.yuceyang.uitest.element.ElementCandidate element = new com.yuceyang.uitest.element.ElementCandidate();
        element.setId(id);
        element.setProjectId("p1");
        element.setSystemId("s1");
        element.setSessionId("sess-1");
        element.setDiscoveryRunId("run-1");
        element.setFingerprint("fp-" + id);
        element.setName(name);
        element.setKind(kind);
        element.setComponent("input");
        return element;
    }

    private static WorkspaceSnapshot workspace() {
        WorkspaceSnapshot workspace = new WorkspaceSnapshot();
        workspace.projectId = "p1";
        SystemUnderTest system = new SystemUnderTest("p1", "订单系统", "order", null);
        system.setId("s1");
        workspace.systems.add(system);

        TreeNode module = node("n1", null, NodeType.MODULE, "订单");
        TreeNode title = node("n2", "n1", NodeType.TITLE1, "查询");
        TreeNode point = node("n3", "n2", NodeType.POINT, "订单列表");
        workspace.nodes.addAll(List.of(module, title, point));
        return workspace;
    }

    private static TreeNode node(String id, String parentId, NodeType type, String name) {
        TreeNode node = new TreeNode("p1", "s1", parentId, type, name);
        node.setId(id);
        return node;
    }
}
