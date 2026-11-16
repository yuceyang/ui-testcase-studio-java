package com.yuceyang.uitest.tree;

import com.yuceyang.uitest.storage.StorageException;
import com.yuceyang.uitest.storage.WorkspaceSnapshot;
import com.yuceyang.uitest.testcase.TestCase;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TreeServiceTest {
    @Test
    void deletingPointUnbindsRatherThanDeletesCases() {
        WorkspaceSnapshot workspace = workspace();
        TreeNode node = node("point", "root");
        workspace.nodes.add(node);
        TestCase testCase = new TestCase("p", "s", "point", "查询订单");
        workspace.testCases.add(testCase);
        TreeService.softDeleteNode(workspace, "point");
        assertThat(node.getDeletedAt()).isNotNull();
        assertThat(workspace.testCases).contains(testCase);
        assertThat(testCase.getPointId()).isNull();
    }

    @Test
    void cannotMoveAcrossSystemsOrIntoOwnDescendant() {
        WorkspaceSnapshot workspace = workspace();
        TreeNode parent = node("parent", null);
        TreeNode child = node("child", "parent");
        TreeNode other = new TreeNode("p", "other-system", null, NodeType.MODULE, "其他系统");
        other.onCreateForStore(); other.setId("other");
        workspace.nodes.add(parent); workspace.nodes.add(child); workspace.nodes.add(other);
        assertThatThrownBy(() -> TreeService.moveNode(workspace, "parent", "other"))
                .isInstanceOf(StorageException.class).hasMessageContaining("跨系统");
        assertThatThrownBy(() -> TreeService.moveNode(workspace, "parent", "child"))
                .isInstanceOf(StorageException.class).hasMessageContaining("后代");
    }

    private static WorkspaceSnapshot workspace() {
        WorkspaceSnapshot result = new WorkspaceSnapshot(); result.projectId = "p"; return result;
    }
    private static TreeNode node(String id, String parent) {
        TreeNode node = new TreeNode("p", "s", parent, NodeType.POINT, id);
        node.onCreateForStore(); node.setId(id); return node;
    }
}
