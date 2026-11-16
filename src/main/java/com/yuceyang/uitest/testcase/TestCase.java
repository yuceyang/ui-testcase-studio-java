package com.yuceyang.uitest.testcase;

import com.yuceyang.uitest.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "test_cases")
public class TestCase extends AuditedEntity {
    @Column(nullable = false)
    private String projectId;
    @Column(nullable = false)
    private String systemId;
    private String pointId;
    @Column(nullable = false)
    private String title;
    @Column(length = 4000)
    private String preconditions;
    @Column(length = 10000)
    private String steps;
    @Column(length = 10000)
    private String expectedResult;
    @Column(length = 10000)
    private String actualResult;
    @Column(nullable = false, length = 20)
    private String status = "DRAFT";
    @Column(length = 32)
    private String caseNo;
    @Column(length = 255)
    private String moduleName;
    @Column(length = 8)
    private String priority;
    @Column(length = 20)
    private String category;
    @Column(length = 4000)
    private String designRequirement;
    @Column(length = 1000)
    private String testTrack;
    @Column(length = 2000)
    private String remark;
    @Column(length = 2000)
    private String paramsText;
    @Column(length = 16)
    private String method;
    @Column(length = 1000)
    private String url;
    @Column(length = 255)
    private String l1;
    @Column(length = 255)
    private String l2;
    @Column(length = 2000)
    private String annotation;
    @Column(length = 36)
    private String screenshotId;

    public TestCase() {}
    public TestCase(String projectId, String systemId, String pointId, String title) {
        this.projectId = projectId;
        this.systemId = systemId;
        this.pointId = pointId;
        this.title = title;
    }
    public String getProjectId() { return projectId; }
    public String getSystemId() { return systemId; }
    public String getPointId() { return pointId; }
    public void setPointId(String pointId) { this.pointId = pointId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getPreconditions() { return preconditions; }
    public void setPreconditions(String preconditions) { this.preconditions = preconditions; }
    public String getSteps() { return steps; }
    public void setSteps(String steps) { this.steps = steps; }
    public String getExpectedResult() { return expectedResult; }
    public void setExpectedResult(String expectedResult) { this.expectedResult = expectedResult; }
    public String getActualResult() { return actualResult; }
    public void setActualResult(String actualResult) { this.actualResult = actualResult; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCaseNo() { return caseNo; }
    public void setCaseNo(String caseNo) { this.caseNo = caseNo; }
    public String getModuleName() { return moduleName; }
    public void setModuleName(String moduleName) { this.moduleName = moduleName; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getDesignRequirement() { return designRequirement; }
    public void setDesignRequirement(String designRequirement) { this.designRequirement = designRequirement; }
    public String getTestTrack() { return testTrack; }
    public void setTestTrack(String testTrack) { this.testTrack = testTrack; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getParamsText() { return paramsText; }
    public void setParamsText(String paramsText) { this.paramsText = paramsText; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getL1() { return l1; }
    public void setL1(String l1) { this.l1 = l1; }
    public String getL2() { return l2; }
    public void setL2(String l2) { this.l2 = l2; }
    public String getAnnotation() { return annotation; }
    public void setAnnotation(String annotation) { this.annotation = annotation; }
    public String getScreenshotId() { return screenshotId; }
    public void setScreenshotId(String screenshotId) { this.screenshotId = screenshotId; }
}
