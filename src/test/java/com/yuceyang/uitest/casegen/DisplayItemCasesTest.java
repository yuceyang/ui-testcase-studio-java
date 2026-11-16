package com.yuceyang.uitest.casegen;

import com.yuceyang.uitest.browser.NetworkExchange;
import com.yuceyang.uitest.browser.NetworkLog;
import com.yuceyang.uitest.storage.WorkspaceSnapshot;
import com.yuceyang.uitest.system.SystemUnderTest;
import com.yuceyang.uitest.testcase.TestCase;
import com.yuceyang.uitest.tree.NodeType;
import com.yuceyang.uitest.tree.TreeNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 展示型内容直接出用例：监控面板上的读数、状态、时间这类只读项不是功能点，
 * 以前只能给每个项建一个功能点再交给 AI，AI 没元素可测就只写出一条「页面加载」。
 * 现在按展示项出：每项一条「展示与数据一致性」，再加一条整块的「刷新与异常态」。
 */
class DisplayItemCasesTest {

    private final CaseGenerationService service = new CaseGenerationService(null, null);

    private static WorkspaceSnapshot workspace() {
        WorkspaceSnapshot snapshot = new WorkspaceSnapshot();
        snapshot.projectId = "p1";
        SystemUnderTest system = new SystemUnderTest("p1", "监控中心", "MON", "");
        system.setId("s1");
        snapshot.systems.add(system);

        TreeNode module = new TreeNode("p1", "s1", null, NodeType.MODULE, "监控中心");
        module.setId("m1");
        module.onCreateForStore();
        TreeNode dir = new TreeNode("p1", "s1", "m1", NodeType.TITLE2, "Master");
        dir.setId("d1");
        dir.onCreateForStore();
        TreeNode point = new TreeNode("p1", "s1", "d1", NodeType.POINT, "主机运行状态");
        point.setId("pt1");
        point.onCreateForStore();
        snapshot.nodes.addAll(List.of(module, dir, point));
        return snapshot;
    }

    @Test
    void oneCasePerItemPlusOneRefreshCase() {
        List<TestCase> cases = service.displayCases(workspace(), "s1", "pt1",
                List.of("主机状态标识", " 处理器使用量 ", ""), "http://host/home", null, null);

        assertThat(cases).extracting(TestCase::getTitle).containsExactly(
                "「主机状态标识」展示与数据一致性验证",
                "「处理器使用量」展示与数据一致性验证",
                "主机运行状态展示区刷新与异常态验证");
        assertThat(cases).allSatisfy(testCase -> {
            assertThat(testCase.getPointId()).isEqualTo("pt1");
            assertThat(testCase.getSystemId()).isEqualTo("s1");
            assertThat(testCase.getStatus()).isEqualTo("DRAFT");
            assertThat(testCase.getUrl()).isEqualTo("http://host/home");
        });
    }

    /** 步骤里必须点到具体展示项与完整路径，否则用例落到别人手里还得自己猜测的是哪个读数。 */
    @Test
    void stepsNameTheItemAndTheFullPath() {
        List<TestCase> cases = service.displayCases(workspace(), "s1", "pt1",
                List.of("内存使用量"), "", null, null);

        TestCase single = cases.getFirst();
        assertThat(single.getSteps()).contains("内存使用量");
        assertThat(single.getPreconditions()).contains("监控中心-Master-主机运行状态");
        assertThat(single.getExpectedResult()).contains("内存使用量");
        assertThat(cases.getLast().getSteps()).contains("内存使用量");
    }

    /** 只读项没有可操作元素，负面用例只到「指标为空/接口异常怎么显示」，不能凭空写输入校验。 */
    @Test
    void noInputValidationCasesAreInvented() {
        List<TestCase> cases = service.displayCases(workspace(), "s1", "pt1",
                List.of("处理器使用量", "磁盘使用量"), "", null, null);

        assertThat(cases).hasSize(3);
        assertThat(cases).filteredOn(c -> "negative".equals(c.getCategory())).singleElement()
                .satisfies(c -> assertThat(c.getTitle()).contains("刷新与异常态"));
        assertThat(cases).noneSatisfy(c -> assertThat(c.getTitle()).contains("必填"));
        assertThat(cases).noneSatisfy(c -> assertThat(c.getTitle()).contains("长度"));
    }

    @Test
    void blankItemsOrUnknownPointProduceNothing() {
        assertThat(service.displayCases(workspace(), "s1", "pt1", List.of(" ", ""), "", null, null)).isEmpty();
        assertThat(service.displayCases(workspace(), "s1", "nope", List.of("处理器使用量"), "", null, null)).isEmpty();
    }

    /** 识别用的那张截图要挂到用例上：用例详情里的「关联截图」不能是空的。 */
    @Test
    void everyDisplayCaseCarriesTheScreenshotItWasRecognizedFrom() {
        WorkspaceSnapshot snapshot = workspace();
        snapshot.screenshots.add(screenshot("shot1", "sess1"));

        List<TestCase> cases = service.displayCases(snapshot, "s1", "pt1",
                List.of("主机状态标识"), "http://host/home", "shot1", null);

        assertThat(cases).hasSize(2)
                .allSatisfy(testCase -> assertThat(testCase.getScreenshotId()).isEqualTo("shot1"));
    }

    /** 展示项的数值来自哪个接口，能用到的信息就是真实抓包：有就填「参考接口」，没有就留空。 */
    @Test
    void paramsComeFromTheCapturedCallOfTheScreenshotSession() {
        NetworkLog log = new NetworkLog();
        log.record(new NetworkExchange("sess1", "GET",
                "http://host/dolphinscheduler/monitor/masters?page=1&size=10", "xhr", 200,
                "{\"code\":0,\"data\":[]}", 1L));
        CaseGenerationService withLog = new CaseGenerationService(null, null, log);
        WorkspaceSnapshot snapshot = workspace();
        snapshot.screenshots.add(screenshot("shot1", "sess1"));

        List<TestCase> cases = withLog.displayCases(snapshot, "s1", "pt1",
                List.of("处理器使用量"), "http://host/home", "shot1", "sess-live");

        assertThat(cases).allSatisfy(testCase -> {
            assertThat(testCase.getParamsText()).contains("参考接口").contains("monitor/masters");
            assertThat(testCase.getMethod()).isEqualTo("GET");
        });
    }

    @Test
    void noCapturedCallMeansNoInventedParams() {
        List<TestCase> cases = service.displayCases(workspace(), "s1", "pt1",
                List.of("处理器使用量"), "http://host/home", "shot1", null);

        assertThat(cases).allSatisfy(testCase -> assertThat(testCase.getParamsText()).isEmpty());
    }

    private static com.yuceyang.uitest.screenshot.Screenshot screenshot(String id, String sessionId) {
        com.yuceyang.uitest.screenshot.Screenshot shot = new com.yuceyang.uitest.screenshot.Screenshot();
        shot.setId(id);
        shot.setSessionId(sessionId);
        shot.setRelativePath("shots/" + id + ".png");
        return shot;
    }
}
