package com.yuceyang.uitest.system;

import com.yuceyang.uitest.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "system_environments")
public class TestEnvironment extends AuditedEntity {
    @Column(nullable = false)
    private String systemId;
    @Column(nullable = false)
    private String name;
    @Column(nullable = false, length = 2000)
    private String baseUrl;
    @Column(nullable = false)
    private boolean defaultEnvironment;

    public TestEnvironment() {}
    public TestEnvironment(String systemId, String name, String baseUrl) {
        this.systemId = systemId;
        this.name = name;
        this.baseUrl = baseUrl;
    }
    public String getSystemId() { return systemId; }
    public void setSystemId(String systemId) { this.systemId = systemId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public boolean isDefaultEnvironment() { return defaultEnvironment; }
    public void setDefaultEnvironment(boolean value) { this.defaultEnvironment = value; }
}
