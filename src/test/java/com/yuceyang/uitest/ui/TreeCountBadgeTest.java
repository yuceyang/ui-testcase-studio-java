package com.yuceyang.uitest.ui;

import com.yuceyang.uitest.tree.NodeType;
import com.yuceyang.uitest.tree.TreeNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 树上的数字 = 该节点下的用例条数。必须能对得上：父节点的数 = 各子节点之和 + 自己直接挂的用例，
 * 整棵根节点 = 顶部「用例 N」。之前数的是「子节点 + 用例」的混合条目，父 9 / 子 4+3 看着就是错的。
 */
class TreeCountBadgeTest {

    private static TreeNode node(String id, NodeType type) {
        TreeNode node = new TreeNode("p1", "s1", null, type, id);
        node.setId(id);
        return node;
    }

    private static TreeNode testCase(String id) {
        return node("case:" + id, NodeType.POINT);
    }

    @Test
    void parentCountIsTheSumOfItsChildren() {
        // 登录模块：登录表单 4 条用例 + 语言切换按钮 3 条 = 7
        TreeNode module = node("m", NodeType.MODULE);
        TreeNode form = node("form", NodeType.POINT);
        TreeNode lang = node("lang", NodeType.POINT);
        Map<String, List<TreeNode>> children = Map.of(
                "m", List.of(form, lang),
                "form", List.of(testCase("a1"), testCase("a2"), testCase("a3"), testCase("a4")),
                "lang", List.of(testCase("b1"), testCase("b2"), testCase("b3")));
        Map<String, Integer> counts = new HashMap<>();

        int total = WorkspaceView.countCases(children, List.of(module), counts);

        assertThat(counts.get("form")).isEqualTo(4);
        assertThat(counts.get("lang")).isEqualTo(3);
        assertThat(counts.get("m")).isEqualTo(7);
        assertThat(total).isEqualTo(7);
    }

    @Test
    void ownCasesAndChildCasesBothCount() {
        TreeNode dir = node("dir", NodeType.TITLE1);
        TreeNode point = node("pt", NodeType.POINT);
        Map<String, List<TreeNode>> children = Map.of(
                "dir", List.of(point, testCase("d1")),
                "pt", List.of(testCase("p1"), testCase("p2")));
        Map<String, Integer> counts = new HashMap<>();

        WorkspaceView.countCases(children, List.of(dir), counts);

        assertThat(counts.get("pt")).isEqualTo(2);
        assertThat(counts.get("dir")).isEqualTo(3);
    }

    @Test
    void deeperNestingKeepsAddingUp() {
        TreeNode module = node("m", NodeType.MODULE);
        TreeNode dir = node("dir", NodeType.TITLE1);
        TreeNode point = node("pt", NodeType.POINT);
        Map<String, List<TreeNode>> children = Map.of(
                "m", List.of(dir),
                "dir", List.of(point),
                "pt", List.of(testCase("c1")));
        Map<String, Integer> counts = new HashMap<>();

        WorkspaceView.countCases(children, List.of(module), counts);

        assertThat(counts).containsEntry("pt", 1).containsEntry("dir", 1).containsEntry("m", 1);
    }

    @Test
    void nodeWithoutCasesShowsNothing() {
        Map<String, Integer> counts = new HashMap<>();

        WorkspaceView.countCases(Map.of(), List.of(node("d1", NodeType.MODULE)), counts);

        assertThat(counts.get("d1")).isZero();
    }
}
