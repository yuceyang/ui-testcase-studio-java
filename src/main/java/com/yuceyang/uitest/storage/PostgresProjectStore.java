package com.yuceyang.uitest.storage;

import com.yuceyang.uitest.audit.OperationLog;
import com.yuceyang.uitest.audit.OperationLogRepository;
import com.yuceyang.uitest.element.ElementCandidate;
import com.yuceyang.uitest.element.ElementRepository;
import com.yuceyang.uitest.project.Project;
import com.yuceyang.uitest.project.ProjectRepository;
import com.yuceyang.uitest.screenshot.CaseScreenshot;
import com.yuceyang.uitest.screenshot.CaseScreenshotRepository;
import com.yuceyang.uitest.screenshot.Screenshot;
import com.yuceyang.uitest.screenshot.ScreenshotRepository;
import com.yuceyang.uitest.system.EnvironmentRepository;
import com.yuceyang.uitest.system.SystemRepository;
import com.yuceyang.uitest.system.SystemUnderTest;
import com.yuceyang.uitest.testcase.TestCase;
import com.yuceyang.uitest.testcase.TestCaseRepository;
import com.yuceyang.uitest.tree.TreeNode;
import com.yuceyang.uitest.tree.TreeNodeRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@ConditionalOnProperty(name = "qts.storage.mode", havingValue = "postgresql")
public class PostgresProjectStore implements ProjectStore {
    private final ProjectRepository projects;
    private final SystemRepository systems;
    private final EnvironmentRepository environments;
    private final TreeNodeRepository nodes;
    private final TestCaseRepository testCases;
    private final ElementRepository elements;
    private final ScreenshotRepository screenshots;
    private final CaseScreenshotRepository caseScreenshots;
    private final OperationLogRepository operationLogs;

    public PostgresProjectStore(ProjectRepository projects, SystemRepository systems,
            EnvironmentRepository environments, TreeNodeRepository nodes, TestCaseRepository testCases,
            ElementRepository elements, ScreenshotRepository screenshots,
            CaseScreenshotRepository caseScreenshots, OperationLogRepository operationLogs) {
        this.projects = projects;
        this.systems = systems;
        this.environments = environments;
        this.nodes = nodes;
        this.testCases = testCases;
        this.elements = elements;
        this.screenshots = screenshots;
        this.caseScreenshots = caseScreenshots;
        this.operationLogs = operationLogs;
    }

    @Override
    public String mode() { return "postgresql"; }

    @Override
    @Transactional
    public WorkspaceSnapshot createProject(String name, String description) {
        Project project = projects.save(new Project(name, description));
        return assemble(project, List.of());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProjectSummary> listProjects() {
        return projects.findAllByDeletedAtIsNull().stream().map(p -> new ProjectSummary(
                p.getId(), p.getName(), p.getDescription(), p.getRevision(), p.getUpdatedAt(),
                Math.toIntExact(systems.countByProjectIdAndDeletedAtIsNull(p.getId())),
                Math.toIntExact(testCases.countByProjectIdAndDeletedAtIsNull(p.getId())))).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public WorkspaceSnapshot getProject(String projectId) {
        Project project = projects.findById(projectId).orElseThrow(() -> new StorageException("项目不存在：" + projectId));
        return assemble(project, systems.findAllByProjectIdAndDeletedAtIsNull(projectId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkspaceSnapshot> findProject(String projectId) {
        return projects.findById(projectId).map(p -> assemble(p, systems.findAllByProjectIdAndDeletedAtIsNull(projectId)));
    }

    @Override
    @Transactional
    public WorkspaceSnapshot saveProject(WorkspaceSnapshot snapshot, long expectedProjectRevision) {
        if (snapshot.project.getRevision() != expectedProjectRevision) {
            throw new ConcurrencyException("项目已被其他操作修改，请刷新后重试");
        }
        try {
            Project saved = projects.saveAndFlush(snapshot.project);
            List<SystemUnderTest> savedSystems = systems.saveAll(snapshot.systems);
            savedSystems.forEach(s -> {
                if (!saved.getId().equals(s.getProjectId())) throw new StorageException("系统不能跨项目保存");
            });
            environments.saveAll(snapshot.environments);
            snapshot.nodes.forEach(n -> validateNode(n, saved.getId(), savedSystems));
            nodes.saveAll(snapshot.nodes);
            snapshot.testCases.forEach(c -> {
                if (!saved.getId().equals(c.getProjectId())) throw new StorageException("用例不能跨项目保存");
            });
            testCases.saveAll(snapshot.testCases);
            elements.saveAll(snapshot.elements);
            screenshots.saveAll(snapshot.screenshots);
            caseScreenshots.saveAll(snapshot.caseScreenshots);
            return getProject(saved.getId());
        } catch (OptimisticLockingFailureException e) {
            throw new ConcurrencyException("数据已被其他用户修改，请刷新后重试");
        } catch (StorageException | ConcurrencyException e) {
            throw e;
        } catch (RuntimeException e) {
            // Hibernate 把写库推迟到提交时，超长/约束/连接类错误都会被包成
            // “Unable to commit against JDBC Connection”，光看这一句没法定位。
            // 这里带上最里层的原因（如 value too long for type character varying(2000)）。
            throw new StorageException("保存失败：" + rootCause(e), e);
        }
    }

    /** 最深一层的异常说明：JDBC/约束错误的真正原因都藏在最里面。 */
    static String rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        if (message == null || message.isBlank()) message = current.getClass().getSimpleName();
        String first = message.lines().findFirst().orElse(message).trim();
        if (first.length() > 240) first = first.substring(0, 240) + "…";
        return first;
    }

    @Override
    @Transactional
    public WorkspaceSnapshot appendAudit(String projectId, OperationLog log) {
        operationLogs.save(log);
        return getProject(projectId);
    }

    private WorkspaceSnapshot assemble(Project project, List<SystemUnderTest> projectSystems) {
        WorkspaceSnapshot result = new WorkspaceSnapshot();
        result.projectId = project.getId();
        result.project = project;
        result.systems = projectSystems;
        result.environments = environments.findAllBySystemIdInAndDeletedAtIsNull(projectSystems.stream().map(SystemUnderTest::getId).toList());
        result.nodes = nodes.findAllByProjectIdAndDeletedAtIsNull(project.getId());
        result.testCases = testCases.findAllByProjectIdAndDeletedAtIsNull(project.getId());
        result.elements = elements.findAllByProjectIdAndDeletedAtIsNull(project.getId());
        result.screenshots = screenshots.findAllByProjectIdAndDeletedAtIsNull(project.getId());
        result.caseScreenshots = caseScreenshots.findAllByTestCaseIdIn(result.testCases.stream().map(TestCase::getId).toList());
        result.operationLogs = operationLogs.findAllByProjectIdOrderByOccurredAtDesc(project.getId());
        return result;
    }

    private void validateNode(TreeNode node, String projectId, List<SystemUnderTest> projectSystems) {
        if (!projectId.equals(node.getProjectId())) throw new StorageException("节点不能跨项目保存");
        if (projectSystems.stream().noneMatch(s -> s.getId().equals(node.getSystemId()))) {
            throw new StorageException("节点所属系统必须属于当前项目");
        }
    }
}
