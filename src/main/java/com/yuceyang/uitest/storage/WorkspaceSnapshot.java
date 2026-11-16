package com.yuceyang.uitest.storage;

import com.yuceyang.uitest.audit.OperationLog;
import com.yuceyang.uitest.element.ElementCandidate;
import com.yuceyang.uitest.project.Project;
import com.yuceyang.uitest.screenshot.CaseScreenshot;
import com.yuceyang.uitest.screenshot.Screenshot;
import com.yuceyang.uitest.system.SystemUnderTest;
import com.yuceyang.uitest.system.TestEnvironment;
import com.yuceyang.uitest.testcase.TestCase;
import com.yuceyang.uitest.tree.TreeNode;
import java.util.ArrayList;
import java.util.List;

public class WorkspaceSnapshot {
    public String projectId;
    public Project project;
    public List<SystemUnderTest> systems = new ArrayList<>();
    public List<TestEnvironment> environments = new ArrayList<>();
    public List<TreeNode> nodes = new ArrayList<>();
    public List<TestCase> testCases = new ArrayList<>();
    public List<ElementCandidate> elements = new ArrayList<>();
    public List<Screenshot> screenshots = new ArrayList<>();
    public List<CaseScreenshot> caseScreenshots = new ArrayList<>();
    public List<OperationLog> operationLogs = new ArrayList<>();
    public String schemaVersion = "1";

    public String getProjectId() { return projectId; }
    public Project getProject() { return project; }
    public List<SystemUnderTest> getSystems() { return systems; }
    public List<TestEnvironment> getEnvironments() { return environments; }
    public List<TreeNode> getNodes() { return nodes; }
    public List<TestCase> getTestCases() { return testCases; }
    public List<ElementCandidate> getElements() { return elements; }
    public List<Screenshot> getScreenshots() { return screenshots; }
    public List<CaseScreenshot> getCaseScreenshots() { return caseScreenshots; }
    public List<OperationLog> getOperationLogs() { return operationLogs; }
    public String getSchemaVersion() { return schemaVersion; }
}
