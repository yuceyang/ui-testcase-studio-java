package com.yuceyang.uitest.browser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 浏览器的「记忆」：每个系统/环境上次访问到哪一页，以及登录态（Cookie + localStorage + IndexedDB）。
 *
 * <p>地址存在 {@code <数据目录>/browser-state/last-urls.properties}，登录态存
 * {@code <数据目录>/browser-state/<项目>-<系统>-<环境>.json}（Playwright storageState 格式）。
 * 两者都是本机缓存，不是业务数据：删掉只等于「忘记密码」，不影响项目内容；
 * 但登录态文件里有会话凭据，只落数据目录，不写日志、不进仓库（data/ 已在 .gitignore）。
 */
@Service
public class BrowserStateStore {
    private static final Logger log = LoggerFactory.getLogger(BrowserStateStore.class);

    private final Path root;
    private final Path urlFile;
    private final Map<String, String> lastUrls = new ConcurrentHashMap<>();
    private final Object urlLock = new Object();

    public BrowserStateStore(@Value("${qts.storage.directory}") String directory) {
        this.root = Path.of(directory).toAbsolutePath().normalize().resolve("browser-state");
        this.urlFile = root.resolve("last-urls.properties");
        loadUrls();
    }

    /** 上次访问的页面；没有记录时返回 empty，调用方退回到环境地址。 */
    public Optional<String> lastUrl(String projectId, String systemId, String environmentId) {
        if (projectId == null || systemId == null || environmentId == null) return Optional.empty();
        return Optional.ofNullable(lastUrls.get(key(projectId, systemId, environmentId)));
    }

    /** 记住这次落到了哪一页；值没变就不写盘（镜像刷新每秒都会调一次）。 */
    public void rememberUrl(String projectId, String systemId, String environmentId, String url) {
        if (projectId == null || systemId == null || environmentId == null) return;
        if (url == null || url.isBlank()) return;
        String previous = lastUrls.put(key(projectId, systemId, environmentId), url.trim());
        if (url.trim().equals(previous)) return;
        synchronized (urlLock) {
            persistUrls();
        }
    }

    /** 登录态文件路径；文件不存在表示这套环境还没登录过。 */
    public Path storageFile(String projectId, String systemId, String environmentId) {
        String name = slug(projectId) + "__" + slug(systemId) + "__" + slug(environmentId) + ".json";
        return root.resolve(name);
    }

    /** 文件名只由 id 派生，不能带路径分隔符或特殊字符。 */
    static String slug(String value) {
        if (value == null || value.isBlank()) return "unknown";
        String cleaned = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.length() <= 80 ? cleaned : cleaned.substring(0, 80);
    }

    private static String key(String projectId, String systemId, String environmentId) {
        return projectId + "|" + systemId + "|" + environmentId;
    }

    private void loadUrls() {
        if (!Files.isRegularFile(urlFile)) return;
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(urlFile)) {
            properties.load(in);
            properties.forEach((k, v) -> {
                if (k != null && v != null) lastUrls.put(k.toString(), v.toString());
            });
        } catch (IOException | IllegalArgumentException e) {
            // 缓存坏了就当没有：重新访问一次会自动重建，不能让启动失败。
            log.warn("网址缓存读取失败，已忽略：{}", e.getMessage());
        }
    }

    private void persistUrls() {
        Properties properties = new Properties();
        properties.putAll(lastUrls);
        try {
            Files.createDirectories(root);
            Path temporary = root.resolve("last-urls.properties.tmp");
            try (OutputStream out = Files.newOutputStream(temporary)) {
                properties.store(out, "每个系统/环境上次访问的页面（本机缓存，可删除）");
            }
            Files.move(temporary, urlFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("网址缓存写入失败：{}", e.getMessage());
        }
    }
}
