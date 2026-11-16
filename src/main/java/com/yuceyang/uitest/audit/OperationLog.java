package com.yuceyang.uitest.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "operation_logs")
public class OperationLog {
    @Id
    private String id;
    @Column(nullable = false)
    private Instant occurredAt;
    private String actor;
    @Column(nullable = false)
    private String operation;
    private String projectId;
    private String targetType;
    private String targetId;
    @Column(length = 8000)
    private String detail;

    public OperationLog() {}
    public OperationLog(String id, Instant occurredAt, String actor, String operation, String projectId, String targetType, String targetId, String detail) {
        this.id = id;
        this.occurredAt = occurredAt;
        this.actor = actor;
        this.operation = operation;
        this.projectId = projectId;
        this.targetType = targetType;
        this.targetId = targetId;
        this.detail = detail;
    }
    public String getId() { return id; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getActor() { return actor; }
    public String getOperation() { return operation; }
    public String getProjectId() { return projectId; }
    public String getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public String getDetail() { return detail; }
}
