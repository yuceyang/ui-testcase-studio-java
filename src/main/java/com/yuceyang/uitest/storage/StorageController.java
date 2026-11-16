package com.yuceyang.uitest.storage;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspace")
public class StorageController {
    private final ProjectStore store;

    public StorageController(ProjectStore store) { this.store = store; }

    @GetMapping("/info")
    public Map<String, String> info() {
        return Map.of("storageMode", store.mode(), "dataSource", "local".equals(store.mode()) ? "本地 JSON" : "PostgreSQL");
    }
}
