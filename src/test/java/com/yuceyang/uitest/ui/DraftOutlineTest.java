package com.yuceyang.uitest.ui;

import com.yuceyang.uitest.tree.NodeType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 识别结果挂到已有目录下时，比落点更浅的上级目录行是重复的：
 * 不能显示成落点的子节点，也不能真的建出来。
 */
class DraftOutlineTest {

    @Test
    void directoriesAtOrAboveTheTargetAreDroppedAndTheirChildrenLift() {
        // 识别结果：模块 → 一级目录「操作」→ 4 个功能点；落点是已有的二级目录「删除按钮」
        WorkspaceView.DraftNode module = node("d1", "北科应急能力评估中心", NodeType.MODULE, null);
        WorkspaceView.DraftNode title = node("d2", "操作", NodeType.TITLE1, "d1");
        List<WorkspaceView.DraftNode> drafts = List.of(module, title,
                node("d3", "确定删除吗", NodeType.POINT, "d2"),
                node("d4", "取消按钮", NodeType.POINT, "d2"),
                node("d5", "确认按钮", NodeType.POINT, "d2"),
                node("d6", "删除按钮", NodeType.POINT, "d2"));

        Map<String, String> parentOf = WorkspaceView.effectiveDraftParents(drafts, NodeType.TITLE2);

        assertThat(parentOf).containsOnlyKeys("d3", "d4", "d5", "d6");
        assertThat(parentOf.values()).containsOnlyNulls();
        assertThat(WorkspaceView.orderDrafts(drafts, parentOf))
                .extracting(draft -> draft.name)
                .containsExactly("确定删除吗", "取消按钮", "确认按钮", "删除按钮");
    }

    @Test
    void aDeeperDirectoryDraftSurvivesUnderTheTarget() {
        WorkspaceView.DraftNode title = node("d1", "操作", NodeType.TITLE1, null);
        WorkspaceView.DraftNode sub = node("d2", "确认弹窗", NodeType.TITLE3, "d1");
        WorkspaceView.DraftNode point = node("d3", "确认按钮", NodeType.POINT, "d2");

        Map<String, String> parentOf = WorkspaceView.effectiveDraftParents(List.of(title, sub, point), NodeType.TITLE2);

        // 一级目录与落点重复被略过，三级目录与功能点原样保留，父子关系不变
        assertThat(parentOf).containsOnlyKeys("d2", "d3");
        assertThat(parentOf.get("d2")).isNull();
        assertThat(parentOf.get("d3")).isEqualTo("d2");
        assertThat(WorkspaceView.orderDrafts(List.of(title, sub, point), parentOf))
                .extracting(draft -> draft.name)
                .containsExactly("确认弹窗", "确认按钮");
    }

    @Test
    void attachingToTheRootKeepsTheWholeDraft() {
        WorkspaceView.DraftNode module = node("d1", "订单", NodeType.MODULE, null);
        WorkspaceView.DraftNode title = node("d2", "查询", NodeType.TITLE1, "d1");
        WorkspaceView.DraftNode point = node("d3", "订单列表", NodeType.POINT, "d2");
        List<WorkspaceView.DraftNode> drafts = List.of(module, title, point);

        Map<String, String> parentOf = WorkspaceView.effectiveDraftParents(drafts, null);

        assertThat(parentOf).containsOnlyKeys("d1", "d2", "d3");
        assertThat(WorkspaceView.orderDrafts(drafts, parentOf))
                .extracting(draft -> draft.name)
                .containsExactly("订单", "查询", "订单列表");
    }

    @Test
    void parentRowsAlwaysComeBeforeTheirChildren() {
        WorkspaceView.DraftNode point = node("p1", "确认按钮", NodeType.POINT, "t1");
        WorkspaceView.DraftNode title = node("t1", "确认弹窗", NodeType.TITLE3, null);

        Map<String, String> parentOf = WorkspaceView.effectiveDraftParents(List.of(point, title), NodeType.TITLE2);

        assertThat(WorkspaceView.orderDrafts(List.of(point, title), parentOf))
                .extracting(draft -> draft.id)
                .containsExactly("t1", "p1");
    }

    private static WorkspaceView.DraftNode node(String id, String name, NodeType type, String parentId) {
        return new WorkspaceView.DraftNode(id, name, type, parentId, null);
    }
}
