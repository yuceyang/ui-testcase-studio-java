package com.yuceyang.uitest.casegen;

import com.yuceyang.uitest.element.ElementCandidate;
import com.yuceyang.uitest.testcase.TestCase;
import com.yuceyang.uitest.tree.NodeType;
import com.yuceyang.uitest.tree.TreeNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 下限没被 AI 遵守时要把条数补上来。
 * 用户那次：下限 4、上限 8，界面上只挂了一个「用户名」输入框，模型照「截图里找不到就只写一条」的规则
 * 只给了 1 条，于是生成台上就真的只有 1 条。下限是给用户看的承诺，必须由程序兜住。
 */
class CaseTopUpTest {

    private final CaseGenerationService service = new CaseGenerationService(null, null);

    private static TreeNode point() {
        TreeNode node = new TreeNode("p1", "s1", null, NodeType.POINT, "用户名");
        node.setId("pt1");
        node.onCreateForStore();
        return node;
    }

    private static CaseGenerationService.Fields fields() {
        return new CaseGenerationService.Fields("pt1", "用户名", "数据源管理", "源中心", "创建源",
                "源中心/创建源/SSH/用户名", "基础测试", "", "用户名");
    }

    private static ElementCandidate input(String name) {
        ElementCandidate element = new ElementCandidate();
        element.setId("el-" + name);
        element.setName(name);
        element.setKind("input");
        element.onCreateForStore();
        return element;
    }

    private static TestCase aiCase(String title) {
        TestCase testCase = new TestCase("p1", "s1", "pt1", title);
        testCase.onCreateForStore();
        testCase.setRemark("");
        return testCase;
    }

    private CaseGenerationService.TopUp topUp(List<TestCase> result,
                                              Map<String, List<ElementCandidate>> elementsByPoint,
                                              int target, TestCase... aiCases) {
        result.addAll(List.of(aiCases));
        CaseGenerationService.Options options =
                new CaseGenerationService.Options(true, false, 4, 8, "", null, "http://host/home");
        return service.topUpToMinimum(result, List.of(point()), Map.of("pt1", target),
                Map.of("pt1", fields()), elementsByPoint, Map.of(), options, Map.of());
    }

    @Test
    void oneAiCaseIsToppedUpTowardsTheTarget() {
        List<TestCase> result = new ArrayList<>();
        CaseGenerationService.TopUp topUp = topUp(result, Map.of("pt1", List.of(input("用户名"))), 5,
                aiCase("源中心页面加载，并通过「创建源」入口确认「用户名」字段所在表单可达"));

        // 目标 5：AI 的 1 条 + 规则补 4 条（输入框的常态/空值/长度/特殊字符）
        assertThat(result).hasSize(5);
        assertThat(topUp.added()).isEqualTo(4);
        assertThat(topUp.shortfalls()).isEmpty();
        assertThat(result.getFirst().getTitle()).contains("源中心页面加载");
        assertThat(result).allSatisfy(c -> assertThat(c.getPointId()).isEqualTo("pt1"));
        assertThat(result).filteredOn(c -> c.getRemark() != null && c.getRemark().contains("规则补齐"))
                .hasSize(4);
        // 已有 AI 用例时不再补一条重复的「界面加载」
        assertThat(result).filteredOn(c -> c.getTitle().contains("界面加载与元素展示")).isEmpty();
    }

    @Test
    void enoughAiCasesAreLeftAlone() {
        List<TestCase> result = new ArrayList<>();
        CaseGenerationService.TopUp topUp = topUp(result, Map.of("pt1", List.of(input("用户名"))), 4,
                aiCase("A"), aiCase("B"), aiCase("C"), aiCase("D"));

        assertThat(topUp.added()).isZero();
        assertThat(topUp.shortfalls()).isEmpty();
        assertThat(result).hasSize(4);
        assertThat(result).noneSatisfy(c -> assertThat(c.getRemark()).contains("规则补齐"));
    }

    @Test
    void nothingIsInventedWhenThePointHasNeitherElementsNorEvidence() {
        List<TestCase> result = new ArrayList<>();

        CaseGenerationService.TopUp topUp = topUp(result, Map.of("pt1", List.of()), 6);

        // 没有元素、也没有「查询/日期」这类证据时只能补一条页面加载，绝不为凑数编字段
        assertThat(topUp.added()).isEqualTo(1);
        assertThat(result).singleElement()
                .satisfies(c -> assertThat(c.getTitle()).contains("界面加载"));
        // 补不够要说出来，界面上才能解释「为什么只有 1 条」
        assertThat(topUp.shortfalls()).containsExactly("用户名 1/4");
    }

    @Test
    void oneAiCaseWithoutElementsStillReportsTheShortfall() {
        List<TestCase> result = new ArrayList<>();
        CaseGenerationService.TopUp topUp = topUp(result, Map.of("pt1", List.of()), 4,
                aiCase("「用户名」字段所在表单可达"));

        assertThat(topUp.added()).isZero();
        assertThat(result).hasSize(1);
        assertThat(topUp.shortfalls()).containsExactly("用户名 1/4");
    }

    @Test
    void ruleCasesNeverReuseATitleThatAlreadyExists() {
        List<TestCase> result = new ArrayList<>();
        topUp(result, Map.of("pt1", List.of(input("用户名"))), 4,
                aiCase("「用户名」正常输入与回显验证"));

        assertThat(result).hasSize(4);
        assertThat(result).extracting(TestCase::getTitle).doesNotHaveDuplicates();
    }

    @Test
    void toppedUpCasesComeAfterTheAiOnesInOrder() {
        List<TestCase> result = new ArrayList<>();
        Map<String, List<ElementCandidate>> byPoint = new LinkedHashMap<>();
        byPoint.put("pt1", List.of(input("用户名")));
        topUp(result, byPoint, 4, aiCase("AI 第一条"));
        assertThat(result.getFirst().getTitle()).isEqualTo("AI 第一条");
    }
}
