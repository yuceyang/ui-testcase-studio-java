package com.yuceyang.uitest.screenshot;

import com.yuceyang.uitest.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "screenshots")
public class Screenshot extends AuditedEntity {
    @Column(nullable = false)
    private String projectId;
    @Column(nullable = false)
    private String systemId;
    private String environmentId;
    @Column(nullable = false)
    private String sessionId;
    @Column(nullable = false)
    private String pageRevision;
    @Column(nullable = false, length = 64)
    private String sha256;
    @Column(nullable = false)
    private String relativePath;
    @Column(nullable = false)
    private int width;
    @Column(nullable = false)
    private int height;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScreenshotStatus status = ScreenshotStatus.REQUESTED;
    private String errorCode;
    @Column(nullable = false)
    private boolean quarantined;

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getSystemId() { return systemId; }
    public void setSystemId(String systemId) { this.systemId = systemId; }
    public String getEnvironmentId() { return environmentId; }
    public void setEnvironmentId(String environmentId) { this.environmentId = environmentId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getPageRevision() { return pageRevision; }
    public void setPageRevision(String pageRevision) { this.pageRevision = pageRevision; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public String getRelativePath() { return relativePath; }
    public void setRelativePath(String relativePath) { this.relativePath = relativePath; }
    public int getWidth() { return width; }
    public void setWidth(int width) { this.width = width; }
    public int getHeight() { return height; }
    public void setHeight(int height) { this.height = height; }
    public ScreenshotStatus getStatus() { return status; }
    public void setStatus(ScreenshotStatus status) { this.status = status; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public boolean isQuarantined() { return quarantined; }
    public void setQuarantined(boolean quarantined) { this.quarantined = quarantined; }
}
