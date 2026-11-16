package com.yuceyang.uitest.screenshot;

public record ScreenshotBinding(String projectId, String systemId, String environmentId,
        String sessionId, String pageRevision) {
    public boolean compatibleWith(Screenshot screenshot) {
        return screenshot != null && screenshot.getStatus() == ScreenshotStatus.READY
                && !screenshot.isQuarantined() && screenshot.getDeletedAt() == null
                && projectId.equals(screenshot.getProjectId())
                && systemId.equals(screenshot.getSystemId())
                && environmentId.equals(screenshot.getEnvironmentId())
                && sessionId.equals(screenshot.getSessionId())
                && pageRevision.equals(screenshot.getPageRevision());
    }
}
