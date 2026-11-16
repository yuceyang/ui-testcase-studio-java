package com.yuceyang.uitest.screenshot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "case_screenshots")
public class CaseScreenshot {
    @Id
    private String id;
    @Column(nullable = false)
    private String testCaseId;
    @Column(nullable = false)
    private String screenshotId;
    @Column(nullable = false)
    private int sortOrder;
    @Column(length = 1000)
    private String caption;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTestCaseId() { return testCaseId; }
    public void setTestCaseId(String testCaseId) { this.testCaseId = testCaseId; }
    public String getScreenshotId() { return screenshotId; }
    public void setScreenshotId(String screenshotId) { this.screenshotId = screenshotId; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public String getCaption() { return caption; }
    public void setCaption(String caption) { this.caption = caption; }
}
