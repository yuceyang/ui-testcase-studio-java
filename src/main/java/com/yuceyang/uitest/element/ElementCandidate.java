package com.yuceyang.uitest.element;

import com.yuceyang.uitest.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

@Entity
@Table(name = "elements")
public class ElementCandidate extends AuditedEntity {
    @Column(nullable = false)
    private String projectId;
    @Column(nullable = false)
    private String systemId;
    @Column(nullable = false)
    private String sessionId;
    @Column(nullable = false)
    private String discoveryRunId;
    @Column(nullable = false)
    private String fingerprint;
    @Column(nullable = false)
    private String name;
    @Column(nullable = false)
    private String kind;
    private String component;
    @Column(length = 8000)
    private String selector;
    @Column(length = 4000)
    private String semanticPath;
    @Column(nullable = false)
    private String framePath = "main";
    @Column(nullable = false)
    private String identityStrength;
    private String exclusionReason;
    @Column(nullable = false)
    private boolean confirmed;
    @Column(nullable = false)
    private boolean archived;

    /** 元素在页面视口中的位置，仅用于镜像叠框与框选，不落库。 */
    @Transient
    private double x;
    @Transient
    private double y;
    @Transient
    private double width;
    @Transient
    private double height;

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getSystemId() { return systemId; }
    public void setSystemId(String systemId) { this.systemId = systemId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getDiscoveryRunId() { return discoveryRunId; }
    public void setDiscoveryRunId(String discoveryRunId) { this.discoveryRunId = discoveryRunId; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getComponent() { return component; }
    public void setComponent(String component) { this.component = component; }
    public String getSelector() { return selector; }
    public void setSelector(String selector) { this.selector = selector; }
    public String getSemanticPath() { return semanticPath; }
    public void setSemanticPath(String semanticPath) { this.semanticPath = semanticPath; }
    public String getFramePath() { return framePath; }
    public void setFramePath(String framePath) { this.framePath = framePath; }
    public String getIdentityStrength() { return identityStrength; }
    public void setIdentityStrength(String identityStrength) { this.identityStrength = identityStrength; }
    public String getExclusionReason() { return exclusionReason; }
    public void setExclusionReason(String exclusionReason) { this.exclusionReason = exclusionReason; }
    public boolean isConfirmed() { return confirmed; }
    public void setConfirmed(boolean confirmed) { this.confirmed = confirmed; }
    public boolean isArchived() { return archived; }
    public void setArchived(boolean archived) { this.archived = archived; }
    public double getX() { return x; }
    public void setX(double x) { this.x = x; }
    public double getY() { return y; }
    public void setY(double y) { this.y = y; }
    public double getWidth() { return width; }
    public void setWidth(double width) { this.width = width; }
    public double getHeight() { return height; }
    public void setHeight(double height) { this.height = height; }
}
