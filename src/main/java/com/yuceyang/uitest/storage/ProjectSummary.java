package com.yuceyang.uitest.storage;

import java.time.Instant;

public record ProjectSummary(String id, String name, String description, long revision,
                             Instant updatedAt, int systemCount, int testCaseCount) {}
