package com.yuceyang.uitest.tree;

import com.yuceyang.uitest.storage.StorageException;
import com.yuceyang.uitest.storage.WorkspaceSnapshot;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

public final class TreeService {
    private TreeService() {}

    public static void softDeleteNode(WorkspaceSnapshot snapshot, String nodeId) {
        TreeNode node = activeNode(snapshot, nodeId);
        Instant now = Instant.now();
        node.setDeletedAt(now);
        snapshot.testCases.stream().filter(c -> nodeId.equals(c.getPointId())).forEach(c -> c.setPointId(null));
        snapshot.nodes.stream().filter(n -> nodeId.equals(n.getParentId()) && n.getDeletedAt() == null)
                .forEach(child -> child.setParentId(node.getParentId()));
    }

    public static void moveNode(WorkspaceSnapshot snapshot, String nodeId, String newParentId) {
        TreeNode node = activeNode(snapshot, nodeId);
        if (nodeId.equals(newParentId)) throw new StorageException("不能将节点移动到自身之下");
        TreeNode parent = newParentId == null ? null : activeNode(snapshot, newParentId);
        if (parent != null && (!node.getSystemId().equals(parent.getSystemId())
                || !node.getProjectId().equals(parent.getProjectId()))) {
            throw new StorageException("不能直接跨系统移动节点，请使用显式复制迁移");
        }
        if (parent != null && descendantIds(snapshot, nodeId).contains(parent.getId())) {
            throw new StorageException("不能将节点移动到自己的后代之下");
        }
        node.setParentId(newParentId);
    }

    private static Set<String> descendantIds(WorkspaceSnapshot snapshot, String rootId) {
        Set<String> ids = new HashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (TreeNode node : snapshot.nodes) {
                if (node.getDeletedAt() != null) continue;
                if ((rootId.equals(node.getParentId()) || ids.contains(node.getParentId())) && ids.add(node.getId())) changed = true;
            }
        }
        return ids;
    }

    private static TreeNode activeNode(WorkspaceSnapshot snapshot, String nodeId) {
        return snapshot.nodes.stream().filter(n -> n.getId().equals(nodeId) && n.getDeletedAt() == null)
                .findFirst().orElseThrow(() -> new StorageException("节点不存在或已删除"));
    }
}
