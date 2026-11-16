package com.yuceyang.uitest.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuceyang.uitest.audit.OperationLog;
import com.yuceyang.uitest.project.Project;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "qts.storage.mode", havingValue = "local")
public class LocalProjectStore implements ProjectStore {
    private final Path projectDirectory;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public LocalProjectStore(@Value("${qts.storage.directory}") String directory,
                             ObjectMapper objectMapper) throws IOException {
        this.projectDirectory = Path.of(directory).toAbsolutePath().normalize().resolve("projects");
        this.objectMapper = objectMapper;
        Files.createDirectories(projectDirectory);
    }

    @Override
    public String mode() { return "local"; }

    @Override
    public WorkspaceSnapshot createProject(String name, String description) {
        Project project = new Project(name, description);
        Instant now = Instant.now();
        project.setId(UUID.randomUUID().toString());
        project.setCreatedAt(now);
        project.setUpdatedAt(now);
        WorkspaceSnapshot snapshot = new WorkspaceSnapshot();
        snapshot.projectId = project.getId();
        snapshot.project = project;
        synchronized (lock(project.getId())) {
            write(snapshot);
            return snapshot;
        }
    }

    @Override
    public List<ProjectSummary> listProjects() {
        try (var paths = Files.list(projectDirectory)) {
            return paths.filter(Files::isDirectory)
                    .map(path -> findProject(path.getFileName().toString()))
                    .flatMap(Optional::stream)
                    .filter(s -> s.project.getDeletedAt() == null)
                    .map(this::summary)
                    .sorted(Comparator.comparing(ProjectSummary::updatedAt).reversed())
                    .toList();
        } catch (IOException e) {
            throw new StorageException("无法读取本地项目列表", e);
        }
    }

    @Override
    public WorkspaceSnapshot getProject(String projectId) {
        return findProject(projectId).orElseThrow(() -> new StorageException("项目不存在：" + projectId));
    }

    @Override
    public Optional<WorkspaceSnapshot> findProject(String projectId) {
        Path file = projectFile(projectId);
        if (!Files.isRegularFile(file)) return Optional.empty();
        synchronized (lock(projectId)) {
            try {
                WorkspaceSnapshot snapshot = objectMapper.readValue(file.toFile(), WorkspaceSnapshot.class);
                if (!projectId.equals(snapshot.projectId) || snapshot.project == null) {
                    throw new StorageException("项目文件标识不一致：" + projectId);
                }
                return Optional.of(snapshot);
            } catch (IOException e) {
                throw new StorageException("项目文件无法读取，未执行自动覆盖：" + file, e);
            }
        }
    }

    @Override
    public WorkspaceSnapshot saveProject(WorkspaceSnapshot snapshot, long expectedProjectRevision) {
        String projectId = snapshot.projectId;
        synchronized (lock(projectId)) {
            WorkspaceSnapshot previous = getProject(projectId);
            if (previous.project.getRevision() != expectedProjectRevision) {
                throw new ConcurrencyException("项目已被其他操作修改，请刷新后重试");
            }
            snapshot.project.setRevision(expectedProjectRevision + 1);
            snapshot.project.setUpdatedAt(Instant.now());
            write(snapshot);
            return snapshot;
        }
    }

    @Override
    public WorkspaceSnapshot appendAudit(String projectId, OperationLog log) {
        WorkspaceSnapshot snapshot = getProject(projectId);
        snapshot.operationLogs.add(log);
        return saveProject(snapshot, snapshot.project.getRevision());
    }

    private ProjectSummary summary(WorkspaceSnapshot snapshot) {
        return new ProjectSummary(snapshot.project.getId(), snapshot.project.getName(),
                snapshot.project.getDescription(), snapshot.project.getRevision(), snapshot.project.getUpdatedAt(),
                snapshot.systems.size(), snapshot.testCases.size());
    }

    private Object lock(String projectId) {
        return locks.computeIfAbsent(projectId, ignored -> new Object());
    }

    private Path projectFile(String projectId) {
        if (projectId == null || !projectId.matches("[a-f0-9\\-]{36}")) {
            throw new StorageException("项目 ID 格式不正确");
        }
        return projectDirectory.resolve(projectId).resolve("workspace.json");
    }

    private void write(WorkspaceSnapshot snapshot) {
        Path file = projectFile(snapshot.projectId);
        Path temp = file.resolveSibling("workspace-" + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), snapshot);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new StorageException("项目保存失败，原文件未替换：" + file, e);
        } finally {
            try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
        }
    }
}
