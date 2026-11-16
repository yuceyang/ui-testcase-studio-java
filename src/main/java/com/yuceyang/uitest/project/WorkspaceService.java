package com.yuceyang.uitest.project;

import com.yuceyang.uitest.browser.BrowserSession;
import com.yuceyang.uitest.browser.BrowserSessionManager;
import com.yuceyang.uitest.element.ElementCandidate;
import com.yuceyang.uitest.element.ElementDiscoveryPipeline;
import com.yuceyang.uitest.element.ElementDiscoveryScript;
import com.yuceyang.uitest.screenshot.Screenshot;
import com.yuceyang.uitest.screenshot.ScreenshotFileStore;
import com.yuceyang.uitest.screenshot.ScreenshotStatus;
import com.yuceyang.uitest.storage.ProjectStore;
import com.yuceyang.uitest.storage.ProjectSummary;
import com.yuceyang.uitest.storage.StorageException;
import com.yuceyang.uitest.storage.WorkspaceSnapshot;
import com.yuceyang.uitest.system.SystemUnderTest;
import com.yuceyang.uitest.system.TestEnvironment;
import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.options.MouseButton;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceService {
    private final ProjectStore store;
    private final BrowserSessionManager browsers;
    private final ElementDiscoveryPipeline elements;
    private final ScreenshotFileStore screenshotFiles;

    public WorkspaceService(ProjectStore store, BrowserSessionManager browsers,
            ElementDiscoveryPipeline elements, ScreenshotFileStore screenshotFiles) {
        this.store = store;
        this.browsers = browsers;
        this.elements = elements;
        this.screenshotFiles = screenshotFiles;
    }

    public String storageMode() { return store.mode(); }
    public List<ProjectSummary> listProjects() { return store.listProjects(); }

    public WorkspaceSnapshot createProject(String name, String description) {
        if (name == null || name.isBlank()) throw new StorageException("项目名称不能为空");
        return store.createProject(name.trim(), description == null ? "" : description.trim());
    }

    public WorkspaceSnapshot getProject(String projectId) { return store.getProject(projectId); }
    public WorkspaceSnapshot save(WorkspaceSnapshot snapshot) { return store.saveProject(snapshot, snapshot.project.getRevision()); }

    public WorkspaceSnapshot addSystem(String projectId, String systemName, String code,
                                       String environmentName, String baseUrl) {
        WorkspaceSnapshot snapshot = getProject(projectId);
        if (systemName == null || systemName.isBlank()) throw new StorageException("系统名称不能为空");
        SystemUnderTest system = new SystemUnderTest(projectId, systemName.trim(),
                code == null || code.isBlank() ? "system" : code.trim(), "");
        system.onCreateForStore();
        snapshot.systems.add(system);
        if (baseUrl != null && !baseUrl.isBlank()) {
            TestEnvironment environment = new TestEnvironment(system.getId(),
                    environmentName == null || environmentName.isBlank() ? "默认环境" : environmentName.trim(), baseUrl.trim());
            environment.onCreateForStore();
            environment.setDefaultEnvironment(true);
            snapshot.environments.add(environment);
        }
        return store.saveProject(snapshot, snapshot.project.getRevision());
    }

    public BrowserSession openBrowser(String systemId, String environmentId, String url) {
        if (url == null || url.isBlank()) throw new StorageException("请输入目标系统地址");
        String projectId = getProjectForSystem(systemId);
        return browsers.open(projectId, systemId, environmentId, url.trim());
    }

    public MirrorImage mirror(String sessionId) {
        BrowserSession session = testSession(sessionId);
        byte[] image = session.read(() -> {
            // 点击/按键后页面通常还要几十到几百毫秒重绘，先等一小会儿避免截到旧画面。
            session.page().waitForTimeout(120);
            return session.page().screenshot();
        }, Duration.ofSeconds(20));
        com.microsoft.playwright.options.ViewportSize viewport = session.page().viewportSize();
        int width = viewport == null ? 1440 : viewport.width;
        int height = viewport == null ? 900 : viewport.height;
        return new MirrorImage("data:image/png;base64," + Base64.getEncoder().encodeToString(image),
                session.pageRevision(), width, height);
    }

    /** 复用当前会话导航；返回最终 URL（可能发生重定向）。 */
    public String navigate(String sessionId, String url) {
        testSession(sessionId);
        if (url == null || url.isBlank()) throw new StorageException("请输入目标系统地址");
        return browsers.navigate(sessionId, url.trim());
    }

    /** 该环境上次访问到哪一页；没有缓存时返回空串，调用方回退到环境地址。 */
    public String lastBrowserUrl(String projectId, String systemId, String environmentId) {
        if (projectId == null || systemId == null || environmentId == null) return "";
        return browsers.lastUrl(projectId, systemId, environmentId).orElse("");
    }

    /** 会话当前真实地址（登录/跳转后的那一页）。 */
    public String currentUrl(String sessionId) {
        testSession(sessionId);
        return browsers.currentUrl(sessionId);
    }

    /** 把镜像上的点击转发到真实页面；坐标是截图像素坐标。 */
    public void click(String sessionId, double x, double y, int button, int clickCount) {
        BrowserSession session = testSession(sessionId);
        MouseButton target = switch (button) {
            case 1 -> MouseButton.MIDDLE;
            case 2 -> MouseButton.RIGHT;
            default -> MouseButton.LEFT;
        };
        session.execute(() -> {
            session.page().mouse().click(x, y, new Mouse.ClickOptions()
                    .setButton(target).setClickCount(Math.max(1, clickCount)));
            session.page().waitForTimeout(150);
            return null;
        }, Duration.ofSeconds(15));
    }

    public void wheel(String sessionId, double deltaX, double deltaY) {
        BrowserSession session = testSession(sessionId);
        session.execute(() -> {
            session.page().mouse().wheel(deltaX, deltaY);
            session.page().waitForTimeout(120);
            return null;
        }, Duration.ofSeconds(15));
    }

    /** 转发键盘输入：可打印字符用 type，功能键用 press。 */
    public void key(String sessionId, String key, String text, boolean control, boolean alt, boolean shift) {
        BrowserSession session = testSession(sessionId);
        session.execute(() -> {
            var keyboard = session.page().keyboard();
            java.util.List<String> modifiers = new ArrayList<>();
            if (control) modifiers.add("Control");
            if (alt) modifiers.add("Alt");
            if (shift) modifiers.add("Shift");
            for (String modifier : modifiers) keyboard.down(modifier);
            try {
                if (text != null && !text.isEmpty()) {
                    keyboard.type(text);
                } else {
                    String name = playWrightKey(key);
                    if (name == null) return null;
                    if (modifiers.isEmpty()) keyboard.press(name);
                    else keyboard.press(String.join("+", modifiers) + "+" + name);
                }
            } finally {
                for (int i = modifiers.size() - 1; i >= 0; i--) keyboard.up(modifiers.get(i));
            }
            return null;
        }, Duration.ofSeconds(15));
    }

    /** 粘贴、中文输入法上屏：整段插入，不模拟逐键。 */
    public void insertText(String sessionId, String text) {
        if (text == null || text.isEmpty()) return;
        BrowserSession session = testSession(sessionId);
        session.execute(() -> {
            session.page().keyboard().insertText(text);
            session.page().waitForTimeout(80);
            return null;
        }, Duration.ofSeconds(15));
    }

    /** 读取页面当前选区或输入框选中文字，供 Ctrl+C 使用。 */
    public String selectionText(String sessionId) {
        BrowserSession session = testSession(sessionId);
        return session.read(() -> String.valueOf(session.page().evaluate("""
                () => {
                  const el = document.activeElement;
                  if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) {
                    try {
                      const s = el.selectionStart, e = el.selectionEnd;
                      if (s != null && e != null && e > s) return el.value.substring(s, e);
                    } catch (ignored) {}
                  }
                  return String(window.getSelection() || '');
                }
                """)), Duration.ofSeconds(10));
    }

    private static String playWrightKey(String key) {
        if (key == null || key.isEmpty()) return null;
        return switch (key) {
            case "Esc" -> "Escape";
            case " " -> " ";
            case "Enter", "Tab", "Backspace", "Delete", "Home", "End", "PageUp", "PageDown",
                 "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight", "Insert", "Escape" -> key;
            default -> key.length() == 1 ? key : null;
        };
    }

    public void back(String sessionId) {
        BrowserSession session = testSession(sessionId);
        session.execute(() -> { session.page().goBack(); return null; }, Duration.ofSeconds(30));
    }

    public void reload(String sessionId) {
        BrowserSession session = testSession(sessionId);
        session.execute(() -> { session.page().reload(); return null; }, Duration.ofSeconds(30));
    }

    public void zoom(String sessionId, double factor) {
        BrowserSession session = testSession(sessionId);
        session.execute(() -> session.page().evaluate("(f) => { document.body.style.zoom = f; return document.body.style.zoom; }", factor),
                Duration.ofSeconds(10));
    }

    public List<ElementCandidate> discoverElements(String sessionId) {
        BrowserSession session = testSession(sessionId);
        List<ElementDiscoveryPipeline.RawElement> raw = session.read(() -> {
            List<ElementDiscoveryPipeline.RawElement> all = new ArrayList<>();
            for (var frame : session.page().frames()) {
                Object result;
                try {
                    result = frame.evaluate(ElementDiscoveryScript.JAVASCRIPT);
                } catch (com.microsoft.playwright.PlaywrightException ignored) {
                    continue;
                }
                if (!(result instanceof List<?> list)) continue;
                for (Object entry : list) {
                    if (!(entry instanceof Map<?, ?> item)) continue;
                    all.add(new ElementDiscoveryPipeline.RawElement(
                            str(item.get("name")), str(item.get("kind")), str(item.get("component")),
                            str(item.get("selector")), str(item.get("semanticPath")),
                            frame == session.page().mainFrame() ? "main" : frame.url() + "#" + frame.name(),
                            bool(item.get("visible")), bool(item.get("occluded")), number(item.get("area")),
                            str(item.get("testId")), str(item.get("stableId")), str(item.get("ariaLabel")),
                            number(item.get("x")), number(item.get("y")), number(item.get("w")), number(item.get("h"))));
                }
            }
            return all;
        }, Duration.ofSeconds(30));
        List<ElementDiscoveryPipeline.NormalizedElement> normalized = elements.normalize(raw);
        WorkspaceSnapshot workspace = store.getProject(session.projectId());
        // 旧版本会把发现结果直接写库；这里把未确认的草稿从工作区剔除，避免再次落库。
        workspace.elements.removeIf(e -> !e.isConfirmed());
        String discoveryRunId = UUID.randomUUID().toString();
        List<ElementCandidate> discovered = new ArrayList<>();
        // 发现结果只保存在本次会话内存里；只有“批量标记为功能点”时才写入数据库。
        for (ElementDiscoveryPipeline.NormalizedElement item : normalized) {
            ElementCandidate candidate = workspace.elements.stream()
                    .filter(ElementCandidate::isConfirmed)
                    .filter(e -> !e.isArchived() && session.systemId().equals(e.getSystemId()))
                    .filter(e -> e.getFingerprint().equals(item.fingerprint()))
                    .findFirst().orElseGet(() -> {
                        ElementCandidate created = new ElementCandidate();
                        created.onCreateForStore();
                        return created;
                    });
            candidate.setProjectId(session.projectId());
            candidate.setSystemId(session.systemId());
            candidate.setSessionId(session.id());
            candidate.setDiscoveryRunId(discoveryRunId);
            candidate.setFingerprint(item.fingerprint());
            candidate.setName(item.name());
            candidate.setKind(item.kind());
            candidate.setComponent(item.component());
            candidate.setSelector(item.selector());
            candidate.setSemanticPath(item.semanticPath());
            candidate.setFramePath(item.framePath());
            candidate.setIdentityStrength(item.identityStrength());
            candidate.setArchived(false);
            candidate.setX(item.x());
            candidate.setY(item.y());
            candidate.setWidth(item.w());
            candidate.setHeight(item.h());
            discovered.add(candidate);
        }
        return discovered;
    }

    public Screenshot captureCurrent(String sessionId) {
        BrowserSession session = testSession(sessionId);
        byte[] png = session.read(() -> session.page().screenshot(), Duration.ofSeconds(20));
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            if (image == null || image.getWidth() < 2 || image.getHeight() < 2) {
                throw new StorageException("截图内容为空，未写入截图库");
            }
            ScreenshotFileStore.StoredScreenshot stored = screenshotFiles.store(png);
            WorkspaceSnapshot workspace = store.getProject(session.projectId());
            Screenshot screenshot = workspace.screenshots.stream()
                    .filter(s -> s.getSha256().equals(stored.sha256()) && s.getSystemId().equals(session.systemId()))
                    .findFirst().orElseGet(() -> {
                        Screenshot created = new Screenshot();
                        created.onCreateForStore();
                        created.setProjectId(session.projectId());
                        created.setSystemId(session.systemId());
                        workspace.screenshots.add(created);
                        return created;
                    });
            screenshot.setEnvironmentId(session.environmentId());
            screenshot.setSessionId(session.id());
            screenshot.setPageRevision(session.pageRevision());
            screenshot.setSha256(stored.sha256());
            screenshot.setRelativePath(stored.relativePath());
            screenshot.setWidth(image.getWidth());
            screenshot.setHeight(image.getHeight());
            screenshot.setStatus(ScreenshotStatus.READY);
            screenshot.setErrorCode(null);
            screenshot.setQuarantined(false);
            store.saveProject(workspace, workspace.project.getRevision());
            return screenshot;
        } catch (java.io.IOException e) {
            throw new StorageException("截图校验失败，未写入截图库", e);
        }
    }

    public String screenshotData(Screenshot screenshot) {
        try (var input = screenshotFiles.open(screenshot.getRelativePath())) {
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(input.readAllBytes());
        } catch (java.io.IOException e) {
            throw new StorageException("截图文件读取失败", e);
        }
    }

    public void closeBrowser(String sessionId) { browsers.close(sessionId); }
    public record MirrorImage(String image, String pageRevision, int width, int height) {}

    private BrowserSession testSession(String sessionId) {
        return browsers.find(sessionId).orElseThrow(() -> new StorageException("浏览器会话不存在或已过期"));
    }

    private String getProjectForSystem(String systemId) {
        return listProjects().stream().map(p -> getProject(p.id())).filter(w -> w.systems.stream().anyMatch(s -> s.getId().equals(systemId)))
                .findFirst().map(w -> w.projectId).orElseThrow(() -> new StorageException("系统不存在"));
    }

    private static String str(Object value) { return value == null ? "" : String.valueOf(value); }
    private static boolean bool(Object value) { return Boolean.TRUE.equals(value); }
    private static double number(Object value) { return value instanceof Number n ? n.doubleValue() : 0; }
}
