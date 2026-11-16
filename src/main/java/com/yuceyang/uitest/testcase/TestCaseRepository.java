package com.yuceyang.uitest.testcase;

import java.util.List;
import org.springframework.data.repository.ListCrudRepository;

public interface TestCaseRepository extends ListCrudRepository<TestCase, String> {
    List<TestCase> findAllByProjectIdAndDeletedAtIsNull(String projectId);
    long countByProjectIdAndDeletedAtIsNull(String projectId);
}
