package com.yuceyang.uitest.screenshot;

import java.util.List;
import org.springframework.data.repository.ListCrudRepository;

public interface CaseScreenshotRepository extends ListCrudRepository<CaseScreenshot, String> {
    List<CaseScreenshot> findAllByTestCaseIdIn(List<String> testCaseIds);
}
