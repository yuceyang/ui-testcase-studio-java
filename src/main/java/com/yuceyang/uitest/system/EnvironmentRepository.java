package com.yuceyang.uitest.system;

import java.util.List;
import org.springframework.data.repository.ListCrudRepository;

public interface EnvironmentRepository extends ListCrudRepository<TestEnvironment, String> {
    List<TestEnvironment> findAllBySystemIdInAndDeletedAtIsNull(List<String> systemIds);
}
