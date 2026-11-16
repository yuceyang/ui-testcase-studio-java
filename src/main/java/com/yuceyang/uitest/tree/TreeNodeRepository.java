package com.yuceyang.uitest.tree;

import java.util.List;
import org.springframework.data.repository.ListCrudRepository;

public interface TreeNodeRepository extends ListCrudRepository<TreeNode, String> {
    List<TreeNode> findAllByProjectIdAndDeletedAtIsNull(String projectId);
}
