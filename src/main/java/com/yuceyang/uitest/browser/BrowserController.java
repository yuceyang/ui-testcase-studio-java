package com.yuceyang.uitest.browser;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/browser")
public class BrowserController {
    private final BrowserSessionManager sessions;

    public BrowserController(BrowserSessionManager sessions) { this.sessions = sessions; }

    @PostMapping("/sessions")
    public Map<String, String> open(@RequestBody OpenSessionRequest request) {
        BrowserSession session = sessions.open(request.projectId(), request.systemId(), request.environmentId(), request.url());
        return Map.of("sessionId", session.id(), "pageRevision", session.pageRevision());
    }

    @GetMapping("/sessions/{sessionId}/mirror")
    public Map<String, String> mirror(@PathVariable String sessionId) {
        BrowserSession session = sessions.find(sessionId).orElseThrow(() -> new BrowserSessionException("会话不存在或已过期", null));
        byte[] image = session.read(() -> session.page().screenshot(), Duration.ofSeconds(20));
        return Map.of("image", "data:image/png;base64," + Base64.getEncoder().encodeToString(image),
                "pageRevision", session.pageRevision());
    }

    public record OpenSessionRequest(@NotBlank String projectId, @NotBlank String systemId,
                                     String environmentId, @NotBlank String url) {}
}
