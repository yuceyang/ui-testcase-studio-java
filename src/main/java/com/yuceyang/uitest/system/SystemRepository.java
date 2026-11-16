package com.yuceyang.uitest.system;

import java.util.List;
import org.springframework.data.repository.ListCrudRepository;

public interface SystemRepository extends ListCrudRepository<SystemUnderTest, String> {
    List<SystemUnderTest> findAllByProjectIdAndDeletedAtIsNull(String projectId);
    long countByProjectIdAndDeletedAtIsNull(String projectId);
}
