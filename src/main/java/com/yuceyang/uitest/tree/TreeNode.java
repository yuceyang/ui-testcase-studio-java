package com.yuceyang.uitest.tree;

import com.yuceyang.uitest.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "tree_nodes")
public class TreeNode extends AuditedEntity {
    @Column(nullable = false)
    private String projectId;
    @Column(nullable = false)
    private String systemId;
    private String parentId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NodeType nodeType;
    @Column(nullable = false)
    private String name;
    @Column(length = 2000)
    private String description;
    @Column(nullable = false)
    private int sortOrder;
    @Column(length = 4000)
    private String nodePath;
    /** 由哪个交互元素标记生成；用于取消标记时精确回收节点。 */
    @Column(length = 36)
    private String elementId;

    public TreeNode() {}
    public TreeNode(String projectId, String systemId, String parentId, NodeType nodeType, String name) {
        this.projectId = projectId;
        this.systemId = systemId;
        this.parentId = parentId;
        this.nodeType = nodeType;
        this.name = name;
    }
    public String getProjectId() { return projectId; }
    public String getSystemId() { return systemId; }
    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }
    public NodeType getNodeType() { return nodeType; }
    public void setNodeType(NodeType nodeType) { this.nodeType = nodeType; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public String getNodePath() { return nodePath; }
    public void setNodePath(String nodePath) { this.nodePath = nodePath; }
    public String getElementId() { return elementId; }
    public void setElementId(String elementId) { this.elementId = elementId; }
}
