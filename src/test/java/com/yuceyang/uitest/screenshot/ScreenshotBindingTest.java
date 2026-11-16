package com.yuceyang.uitest.screenshot;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ScreenshotBindingTest {
    @Test
    void onlyReadyImageFromSamePageRevisionCanBeBound() {
        Screenshot image = image(ScreenshotStatus.READY);
        assertThat(binding().compatibleWith(image)).isTrue();
        image.setStatus(ScreenshotStatus.STALE);
        assertThat(binding().compatibleWith(image)).isFalse();
        image.setStatus(ScreenshotStatus.READY);
        image.setPageRevision("different");
        assertThat(binding().compatibleWith(image)).isFalse();
    }

    @Test
    void quarantinedOrCrossSystemImageCannotBeBound() {
        Screenshot image = image(ScreenshotStatus.READY);
        image.setQuarantined(true);
        assertThat(binding().compatibleWith(image)).isFalse();
        image.setQuarantined(false);
        image.setSystemId("other-system");
        assertThat(binding().compatibleWith(image)).isFalse();
    }

    private static ScreenshotBinding binding() {
        return new ScreenshotBinding("project", "system", "environment", "session", "revision");
    }
    private static Screenshot image(ScreenshotStatus status) {
        Screenshot image = new Screenshot();
        image.setProjectId("project");
        image.setSystemId("system");
        image.setEnvironmentId("environment");
        image.setSessionId("session");
        image.setPageRevision("revision");
        image.setStatus(status);
        return image;
    }
}
