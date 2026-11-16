package com.yuceyang.uitest.ai;

import com.yuceyang.uitest.tree.NodeType;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 截图识别：用户提示要原样进请求，分组结果要落成「二级标题 + 组内功能点」，不能平铺成一层。
 */
class RegionProposalParseTest {
    private final TreeAdvisor advisor = new TreeAdvisor(null);

    @Test
    void hintAndReferenceCountGoIntoThePrompt() {
        String prompt = TreeAdvisor.regionPrompt("演示系统", "http://host/home",
                "这是数据源类型选择弹窗，按三组归类，只建组名", 1, 2);

        assertThat(prompt).contains("用户提示（优先遵守）").contains("按三组归类");
        assertThat(prompt).contains("最后 2 张是用户补充的参考图");
        assertThat(prompt).contains("只针对第 1 张图");
        assertThat(prompt).contains("演示系统").contains("http://host/home");
    }

    @Test
    void singleImagePromptHasNoReferenceWording() {
        String prompt = TreeAdvisor.regionPrompt("演示系统", "http://host/home", null, 1, 0);

        assertThat(prompt).doesNotContain("参考图");
        assertThat(prompt).doesNotContain("用户提示");
        assertThat(prompt).doesNotContain("多块");
    }

    /** 多个框选区域：告诉模型前 K 张都是识别对象，别把同一块拆成两套、也别只认第一张。 */
    @Test
    void multipleRegionsAreAllDescribedAsRecognitionTargets() {
        String prompt = TreeAdvisor.regionPrompt("演示系统", "http://host/home",
                "搜索栏和操作按钮都要", 2, 1);

        assertThat(prompt).contains("共 3 张图");
        assertThat(prompt).contains("前 2 张是用户在同一页面上框选出来的 2 块互不相邻的区域");
        assertThat(prompt).contains("只针对前 2 张图");
        assertThat(prompt).contains("最后 1 张是用户补充的参考图");
        assertThat(prompt).doesNotContain("只针对第 1 张图");
    }

    @Test
    void groupedPointsBecomeSecondLevelTitles() {
        String json = """
                {"name":"选择源类型","summary":"数据源类型选择弹窗",
                 "points":[
                   {"name":"关系型数据库","type":"二级标题","children":[
                      {"name":"MySQL","type":"功能点"},{"name":"ORACLE","type":"功能点"}]},
                   {"name":"大数据与数仓","type":"二级标题","children":[{"name":"SPARK","type":"功能点"}]}]}
                """;

        TreeAdvisor.RegionProposal proposal = advisor.parseRegion(json, "演示系统", null);

        assertThat(proposal.name()).isEqualTo("选择源类型");
        assertThat(proposal.nodes()).extracting(TreeAdvisor.Proposition::name)
                .contains("关系型数据库", "MySQL", "ORACLE", "大数据与数仓", "SPARK");
        TreeAdvisor.Proposition group = proposal.nodes().stream()
                .filter(n -> n.name().equals("关系型数据库")).findFirst().orElseThrow();
        assertThat(group.type()).isEqualTo(NodeType.TITLE2);
        assertThat(group.parentId()).isEqualTo("region");
        assertThat(proposal.nodes()).filteredOn(n -> n.name().equals("MySQL"))
                .allSatisfy(n -> {
                    assertThat(n.parentId()).isEqualTo(group.id());
                    assertThat(n.type()).isEqualTo(NodeType.POINT);
                });
    }

    @Test
    void flatPointsStayDirectlyUnderTheRegion() {
        String json = """
                {"name":"任务实例","summary":"",
                 "points":[{"name":"状态下拉","type":"功能点"},{"name":"时间范围","type":"功能点"}]}
                """;

        TreeAdvisor.RegionProposal proposal = advisor.parseRegion(json, "演示系统", null);

        assertThat(proposal.nodes()).filteredOn(n -> n.type() == NodeType.POINT)
                .hasSize(2)
                .allSatisfy(n -> assertThat(n.parentId()).isEqualTo("region"));
    }

    @Test
    void emptyAnswerFallsBackToAPlaceholderPoint() {
        TreeAdvisor.RegionProposal proposal = advisor.parseRegion("{\"points\":[]}", "演示系统", "截图选区");

        assertThat(proposal.name()).isEqualTo("截图选区");
        assertThat(proposal.nodes()).filteredOn(n -> n.type() == NodeType.POINT).hasSize(1);
    }
}
