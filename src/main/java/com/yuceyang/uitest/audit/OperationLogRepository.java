package com.yuceyang.uitest.audit;

import java.util.List;
import org.springframework.data.repository.ListCrudRepository;

public interface OperationLogRepository extends ListCrudRepository<OperationLog, String> {
    List<OperationLog> findAllByProjectIdOrderByOccurredAtDesc(String projectId);
}
