package com.yuceyang.uitest.ui;

import com.yuceyang.uitest.storage.WorkspaceSnapshot;
import com.yuceyang.uitest.tree.NodeType;
import com.yuceyang.uitest.tree.TreeNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 框选识别 / 自动建树的落库计划：草稿全是目录时「只入功能点」必须一个都不建，
 * 「入库（含目录）」必须真的把目录挂到落点下面。用户那次点的就是前者，结果 0 个节点。
 */
class DraftPersistPlanTest {

    /** 落点：已有的模块「源中心」。 */
    private static WorkspaceSnapshot snapshotWithAnchor() {
        WorkspaceSnapshot snapshot = new WorkspaceSnapshot();
        snapshot.projectId = "p1";
        TreeNode anchor = new TreeNode("p1", "s1", null, NodeType.MODULE, "源中心");
        anchor.setId("anchor");
        anchor.onCreateForStore();
        snapshot.nodes.add(anchor);
        return snapshot;
    }

    private static WorkspaceView.DraftNode draft(String id, String name, NodeType type, String parentId) {
        return new WorkspaceView.DraftNode(id, name, type, parentId, null);
    }

    private static WorkspaceView.DraftNode display(String id, String name, String parentId) {
        return new WorkspaceView.DraftNode(id, name, NodeType.POINT, parentId, null, true);
    }

    @Test
    void pointsOnlyCreatesNothingWhenEveryRowIsADirectory() {
        // 识别结果：模块（被落点吸收）→ 两个一级目录；点「只入功能点」得到的就是「已入库 0 个功能点」
        List<WorkspaceView.DraftNode> drafts = List.of(
                draft("r", "北科应急能力评估系统", NodeType.MODULE, null),
                draft("d1", "创建源", NodeType.TITLE1, "r"),
                draft("d2", "请输入关键字搜索", NodeType.TITLE1, "r"));

        WorkspaceView.DraftPlan plan = WorkspaceView.planDrafts(
                snapshotWithAnchor(), drafts, "s1", "anchor", NodeType.MODULE, true);

        assertThat(plan.created()).isEmpty();
        assertThat(plan.pointIds()).isEmpty();
        assertThat(plan.omitted()).isEqualTo(2);
    }

    @Test
    void directoriesAreActuallyCreatedUnderTheAnchor() {
        List<WorkspaceView.DraftNode> drafts = List.of(
                draft("r", "北科应急能力评估系统", NodeType.MODULE, null),
                draft("d1", "创建源", NodeType.TITLE1, "r"),
                draft("d2", "请输入关键字搜索", NodeType.TITLE1, "r"));

        WorkspaceView.DraftPlan plan = WorkspaceView.planDrafts(
                snapshotWithAnchor(), drafts, "s1", "anchor", NodeType.MODULE, false);

        assertThat(plan.created()).extracting(TreeNode::getName)
                .containsExactly("创建源", "请输入关键字搜索");
        assertThat(plan.created()).allSatisfy(node -> {
            assertThat(node.getNodeType()).isEqualTo(NodeType.TITLE1);
            assertThat(node.getParentId()).isEqualTo("anchor");
            assertThat(node.getSystemId()).isEqualTo("s1");
        });
        assertThat(plan.created()).extracting(TreeNode::getSortOrder).containsExactly(1, 2);
        assertThat(plan.omitted()).isZero();
    }

    @Test
    void pointsUnderSkippedDirectoriesHangDirectlyOnTheAnchor() {
        List<WorkspaceView.DraftNode> drafts = List.of(
                draft("r", "北科应急能力评估系统", NodeType.MODULE, null),
                draft("d1", "创建源", NodeType.TITLE1, "r"),
                draft("p1", "创建源按钮", NodeType.POINT, "d1"));

        WorkspaceView.DraftPlan plan = WorkspaceView.planDrafts(
                snapshotWithAnchor(), drafts, "s1", "anchor", NodeType.MODULE, true);

        assertThat(plan.created()).singleElement().satisfies(node -> {
            assertThat(node.getName()).isEqualTo("创建源按钮");
            assertThat(node.getNodeType()).isEqualTo(NodeType.POINT);
            assertThat(node.getParentId()).isEqualTo("anchor");
        });
        assertThat(plan.pointIds()).containsExactly(plan.created().getFirst().getId());
        assertThat(plan.omitted()).isEqualTo(1);
    }

    /**
     * 用户那次：落点选了已有的「创建源」（二级目录），草稿是 模块 → 一级目录 → 14 个二级目录。
     * 旧规则按层级一刀切，把 14 个二级目录也当成「同级重复」全删了，草稿变成 0 个节点、入不了库。
     * 正确行为：只跳过落点已经提供的上级（模块、一级目录），二级目录是内容，要挂到落点下面。
     */
    @Test
    void siblingLevelContentUnderADeeperAnchorIsKept() {
        List<WorkspaceView.DraftNode> drafts = new java.util.ArrayList<>();
        drafts.add(draft("r", "北科应急能力适应性评估数据资源管理中心", NodeType.MODULE, null));
        drafts.add(draft("region", "选择源类型", NodeType.TITLE1, "r"));
        for (int i = 0; i < 14; i++) drafts.add(draft("d" + i, "类型" + i, NodeType.TITLE2, "region"));

        WorkspaceView.DraftPlan plan = WorkspaceView.planDrafts(
                snapshotWithAnchor(), drafts, "s1", "anchor", "创建源", NodeType.TITLE2, false);

        assertThat(plan.created()).hasSize(14);
        assertThat(plan.created()).allSatisfy(node -> {
            assertThat(node.getNodeType()).isEqualTo(NodeType.TITLE2);
            assertThat(node.getParentId()).isEqualTo("anchor");
        });
        assertThat(plan.redundant()).isEqualTo(2);
    }

    /** 草稿里就带着落点自己那一行（同名同类型）时，那一行不重复建，它下面的内容提上来。 */
    @Test
    void theAnchorRowItselfIsSkippedNotItsWholeSubtree() {
        List<WorkspaceView.DraftNode> drafts = List.of(
                draft("r", "北科应急能力评估系统", NodeType.MODULE, null),
                draft("region", "选择源类型", NodeType.TITLE1, "r"),
                draft("d1", "MYSQL", NodeType.TITLE2, "region"),
                draft("d2", "ORACLE", NodeType.TITLE2, "region"));

        WorkspaceView.DraftPlan plan = WorkspaceView.planDrafts(
                snapshotWithAnchor(), drafts, "s1", "anchor", "选择源类型", NodeType.TITLE1, false);

        assertThat(plan.created()).extracting(TreeNode::getName).containsExactly("MYSQL", "ORACLE");
        assertThat(plan.created()).allSatisfy(node -> assertThat(node.getParentId()).isEqualTo("anchor"));
        assertThat(plan.redundant()).isEqualTo(2);
    }

    /**
     * 用户那次：落点 Master（二级目录），识别出来的 6 行全是展示项（主机状态标识、处理器使用量…）。
     * 它们不该在树里各占一个功能点：一个节点都不建，也不该被算成「落点已提供、略过」的上级。
     */
    @Test
    void displayRowsCreateNoNodesAndAreNotCountedAsRedundant() {
        List<WorkspaceView.DraftNode> drafts = List.of(
                display("a", "主机状态标识", null),
                display("b", "主机与心跳时间", null),
                display("c", "处理器使用量", null));

        WorkspaceView.DraftPlan plan = WorkspaceView.planDrafts(
                snapshotWithAnchor(), drafts, "s1", "anchor", "Master", NodeType.TITLE2, true);

        assertThat(plan.created()).isEmpty();
        assertThat(plan.pointIds()).isEmpty();
        assertThat(plan.redundant()).isZero();
        assertThat(plan.omitted()).isZero();
        assertThat(WorkspaceView.displayItemNames(drafts))
                .containsExactly("主机状态标识", "主机与心跳时间", "处理器使用量");
    }

    /** 混批：功能点照建，显示项既不建节点也不影响功能点数量与「略过」的统计。 */
    @Test
    void mixedBatchKeepsPointsAndSkipsDisplayRows() {
        List<WorkspaceView.DraftNode> drafts = List.of(
                draft("r", "Master", NodeType.TITLE2, null),
                draft("p1", "刷新按钮", NodeType.POINT, "r"),
                display("a", "内存使用量", "r"));

        WorkspaceView.DraftPlan plan = WorkspaceView.planDrafts(
                snapshotWithAnchor(), drafts, "s1", "anchor", "Master", NodeType.TITLE2, true);

        assertThat(plan.created()).extracting(TreeNode::getName).containsExactly("刷新按钮");
        assertThat(plan.pointIds()).hasSize(1);
        assertThat(plan.redundant()).isEqualTo(1);
        assertThat(WorkspaceView.displayItemNames(drafts)).containsExactly("内存使用量");
    }

    @Test
    void displayPointIsNamedAfterTheRegionButNeverAfterTheAnchor() {
        assertThat(WorkspaceView.displayPointName("主机运行状态", "Master")).isEqualTo("主机运行状态");
        assertThat(WorkspaceView.displayPointName("  ", "Master")).isEqualTo("界面展示检查");
        assertThat(WorkspaceView.displayPointName(null, "Master")).isEqualTo("界面展示检查");
        // 区域名与落点同名时不能建出「Master 下的 Master」
        assertThat(WorkspaceView.displayPointName("Master", "Master")).isEqualTo("Master展示检查");
    }

    @Test
    void displayItemNamesDropBlanksAndDuplicates() {
        List<WorkspaceView.DraftNode> drafts = List.of(
                display("a", " 处理器使用量 ", null),
                display("b", "处理器使用量", null),
                display("c", "", null));

        assertThat(WorkspaceView.displayItemNames(drafts)).containsExactly("处理器使用量");
    }

    @Test
    void sameNameInTheSameBatchIsReusedInsteadOfDuplicated() {
        List<WorkspaceView.DraftNode> drafts = List.of(
                draft("d1", "创建源", NodeType.TITLE1, null),
                draft("d2", "创建源", NodeType.TITLE1, null));

        WorkspaceView.DraftPlan plan = WorkspaceView.planDrafts(
                snapshotWithAnchor(), drafts, "s1", "anchor", NodeType.MODULE, false);

        assertThat(plan.created()).hasSize(1);
        assertThat(plan.reused()).isEqualTo(1);
    }

    @Test
    void existingNodeWithTheSameNameIsReused() {
        WorkspaceSnapshot snapshot = snapshotWithAnchor();
        TreeNode existing = new TreeNode("p1", "s1", "anchor", NodeType.TITLE1, "创建源");
        existing.setId("n-existing");
        existing.onCreateForStore();
        snapshot.nodes.add(existing);

        WorkspaceView.DraftPlan plan = WorkspaceView.planDrafts(snapshot,
                List.of(draft("d1", "创建源", NodeType.TITLE1, null)), "s1", "anchor", NodeType.MODULE, false);

        assertThat(plan.created()).isEmpty();
        assertThat(plan.reused()).isEqualTo(1);
    }
}
