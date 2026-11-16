package com.yuceyang.uitest.browser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 地址栏的缓存：每个系统/环境记住上次访问的页面，重启后仍是那一页。
 * 登录态文件按同样的键隔离，不能串到别的环境上。
 */
class BrowserStateStoreTest {

    @Test
    void remembersTheLastPagePerEnvironment(@TempDir Path dir) {
        BrowserStateStore store = new BrowserStateStore(dir.toString());
        store.rememberUrl("p1", "s1", "e1", "http://host/a");
        store.rememberUrl("p1", "s1", "e2", "http://host/b");

        assertThat(store.lastUrl("p1", "s1", "e1")).contains("http://host/a");
        assertThat(store.lastUrl("p1", "s1", "e2")).contains("http://host/b");
        assertThat(store.lastUrl("p1", "s1", "e3")).isEmpty();
    }

    @Test
    void survivesRestart(@TempDir Path dir) {
        new BrowserStateStore(dir.toString()).rememberUrl("p", "s", "e",
                "http://demo.example.com:12345/dolphinscheduler/ui/home");

        BrowserStateStore restarted = new BrowserStateStore(dir.toString());

        assertThat(restarted.lastUrl("p", "s", "e"))
                .contains("http://demo.example.com:12345/dolphinscheduler/ui/home");
    }

    @Test
    void storageFileIsPerEnvironmentAndPathSafe(@TempDir Path dir) {
        BrowserStateStore store = new BrowserStateStore(dir.toString());

        Path first = store.storageFile("p", "s1", "e1");
        Path second = store.storageFile("p", "s2", "e1");

        assertThat(first).isNotEqualTo(second);
        assertThat(first.getParent()).isEqualTo(dir.resolve("browser-state"));
        assertThat(first.getFileName().toString()).doesNotContain("/", "\\", "..");
        assertThat(BrowserStateStore.slug("../../etc/passwd")).doesNotContain("/", "\\");
    }

    @Test
    void brokenCacheFileDoesNotBreakStartup(@TempDir Path dir) throws IOException {
        Path stateDir = Files.createDirectories(dir.resolve("browser-state"));
        Files.writeString(stateDir.resolve("last-urls.properties"), "\u0000\u0001 not a properties file");

        BrowserStateStore store = new BrowserStateStore(dir.toString());

        assertThat(store.lastUrl("p", "s", "e")).isEmpty();
        store.rememberUrl("p", "s", "e", "http://host/x");
        assertThat(new BrowserStateStore(dir.toString()).lastUrl("p", "s", "e")).contains("http://host/x");
    }
}
