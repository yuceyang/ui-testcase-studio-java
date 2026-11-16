package com.yuceyang.uitest.storage;

import com.yuceyang.uitest.audit.OperationLog;
import java.util.List;
import java.util.Optional;

public interface ProjectStore {
    String mode();
    WorkspaceSnapshot createProject(String name, String description);
    List<ProjectSummary> listProjects();
    WorkspaceSnapshot getProject(String projectId);
    Optional<WorkspaceSnapshot> findProject(String projectId);
    WorkspaceSnapshot saveProject(WorkspaceSnapshot snapshot, long expectedProjectRevision);
    WorkspaceSnapshot appendAudit(String projectId, OperationLog log);
}
