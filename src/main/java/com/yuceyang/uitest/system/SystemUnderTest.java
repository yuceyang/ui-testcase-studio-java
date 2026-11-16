package com.yuceyang.uitest.system;

import com.yuceyang.uitest.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "systems")
public class SystemUnderTest extends AuditedEntity {
    @Column(nullable = false)
    private String projectId;
    @Column(nullable = false)
    private String name;
    @Column(nullable = false)
    private String code;
    @Column(length = 1000)
    private String description;

    public SystemUnderTest() {}
    public SystemUnderTest(String projectId, String name, String code, String description) {
        this.projectId = projectId;
        this.name = name;
        this.code = code;
        this.description = description;
    }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
