package com.yuceyang.uitest.screenshot;

import java.util.List;
import org.springframework.data.repository.ListCrudRepository;

public interface ScreenshotRepository extends ListCrudRepository<Screenshot, String> {
    List<Screenshot> findAllByProjectIdAndDeletedAtIsNull(String projectId);
}
