package com.yuceyang.uitest.element;

import java.util.List;
import org.springframework.data.repository.ListCrudRepository;

public interface ElementRepository extends ListCrudRepository<ElementCandidate, String> {
    List<ElementCandidate> findAllByProjectIdAndDeletedAtIsNull(String projectId);
}
