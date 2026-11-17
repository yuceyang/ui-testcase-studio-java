package com.yuceyang.uitest.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class BrowserSessionManager {
    private static final Logger log = LoggerFactory.getLogger(BrowserSessionManager.class);
    private final boolean enabled;
    private final String channel;
    private final boolean headless;
    private final String proxy;
    private final Duration idleTimeout;
    private final Object lifecycleLock = new Object();
    private final Map<String, BrowserSession> sessions = new ConcurrentHashMap<>();
    private final NetworkLog networkLog;
    private final BrowserStateStore stateStore;
    private Playwright playwright;
    private Browser browser;

    public BrowserSessionManager(@Value("${qts.browser.enabled}") boolean enabled,
            @Value("${qts.browser.channel}") String channel,
            @Value("${qts.browser.headless}") boolean headless,
            @Value("${qts.browser.proxy:}") String proxy,
            @Value("${qts.browser.idle-timeout}") Duration idleTimeout,
            NetworkLog networkLog, BrowserStateStore stateStore) {
        this.enabled = enabled;
        this.channel = channel;
        this.headless = headless;
        this.proxy = proxy;
        this.idleTimeout = idleTimeout;
        this.networkLog = networkLog;
        this.stateStore = stateStore;
    }

    public BrowserSession open(String projectId, String systemId, String environmentId, String url) {
        if (!enabled) throw new BrowserSessionException("浏览器功能未启用", null);
        validateHttpUrl(url);
        synchronized (lifecycleLock) {
            ensureBrowser();
            BrowserContext context = newContext(projectId, systemId, environmentId);
            BrowserSession session = new BrowserSession(projectId, systemId, environmentId, context, networkLog);
            sessions.put(session.id(), session);
            try {
                navigateInSession(session, url);
                return session;
            } catch (RuntimeException e) {
                sessions.remove(session.id());
                session.close();
                throw e;
            }
        }
    }

    /**
     * 新上下文：同一套系统/环境复用上次存下的登录态，重启后不用重新登录。
     * 缓存文件损坏时丢缓存重开，宁可直接打开登录页，也不能让「前往」报错。
     */
    private BrowserContext newContext(String projectId, String systemId, String environmentId) {
        Browser.NewContextOptions fresh = new Browser.NewContextOptions()
                .setViewportSize(1440, 900)
                .setDeviceScaleFactor(1.0);
        Path state = stateStore.storageFile(projectId, systemId, environmentId);
        if (!hasState(state)) return browser.newContext(fresh);
        try {
            return browser.newContext(new Browser.NewContextOptions()
                    .setViewportSize(1440, 900)
                    .setDeviceScaleFactor(1.0)
                    .setStorageStatePath(state));
        } catch (PlaywrightException e) {
            log.warn("登录态缓存不可用，已丢弃并改为重新登录：{}", e.getMessage());
            discard(state);
            return browser.newContext(fresh);
        }
    }

    private static boolean hasState(Path state) {
        try {
            return Files.isRegularFile(state) && Files.size(state) > 2;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private static void discard(Path state) {
        try {
            Files.deleteIfExists(state);
        } catch (java.io.IOException e) {
            log.debug("删除失效登录态缓存失败：{}", e.getMessage());
        }
    }

    /** 上次访问的页面，用于新会话/重启后把地址栏填回原处。 */
    public Optional<String> lastUrl(String projectId, String systemId, String environmentId) {
        return stateStore.lastUrl(projectId, systemId, environmentId);
    }

    public String currentUrl(String sessionId) {
        return find(sessionId).map(BrowserSession::currentUrl).orElse("");
    }

    /** 复用已有会话导航，避免每次“前往”都新建上下文。 */
    public String navigate(String sessionId, String url) {
        validateHttpUrl(url);
        BrowserSession session = find(sessionId)
                .orElseThrow(() -> new BrowserSessionException("浏览器会话不存在或已过期", null));
        return navigateInSession(session, url);
    }

    private String navigateInSession(BrowserSession session, String url) {
        String landed = session.execute(() -> {
            Page page = session.page();
            Page.NavigateOptions options = new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(30000);
            try {
                page.navigate(url, options);
            } catch (PlaywrightException first) {
                // 只有“连接被重置 / 空响应 / 超时”这类抖动值得重试；域名解析失败、端口拒绝重试也没用。
                String reason = shortMessage(first);
                boolean worthRetry = reason.contains("ERR_EMPTY_RESPONSE")
                        || reason.contains("ERR_CONNECTION_RESET")
                        || reason.contains("ERR_CONNECTION_CLOSED")
                        || reason.contains("ERR_NETWORK_CHANGED")
                        || reason.contains("Timeout");
                if (worthRetry) {
                    page.waitForTimeout(600);
                    try {
                        page.navigate(url, options);
                    } catch (PlaywrightException second) {
                        throw friendlyNavigationError(url, second);
                    }
                } else {
                    throw friendlyNavigationError(url, first);
                }
            }
            try {
                // 单页应用常在 load 之后才渲染，最多再等 4 秒；等不到也继续截当前画面。
                page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(4000));
            } catch (PlaywrightException ignored) {
            }
            page.waitForTimeout(350);
            return page.url();
        }, Duration.ofSeconds(40));
        rememberUrl(session, landed);
        flushState(session);
        return landed;
    }

    /** 该地址下当前有多少 Cookie，用来判断“有没有登录态”。 */
    public int cookieCount(String sessionId, String url) {
        BrowserSession session = find(sessionId)
                .orElseThrow(() -> new BrowserSessionException("浏览器会话不存在或已过期", null));
        if (url == null || url.isBlank()) return 0;
        return session.execute(() -> session.context().cookies(url).size(), Duration.ofSeconds(8));
    }

    /** 清掉登录态：Cookie + localStorage / sessionStorage。 */
    public void clearLoginState(String sessionId) {
        BrowserSession session = find(sessionId)
                .orElseThrow(() -> new BrowserSessionException("浏览器会话不存在或已过期", null));
        session.execute(() -> {
            session.context().clearCookies();
            try {
                session.page().evaluate("() => { try { localStorage.clear(); sessionStorage.clear(); } catch (e) {} }");
            } catch (PlaywrightException ignored) {
                // about:blank 等页面没有 storage
            }
            return null;
        }, Duration.ofSeconds(10));
    }

    private void rememberUrl(BrowserSession session, String url) {
        try {
            stateStore.rememberUrl(session.projectId(), session.systemId(), session.environmentId(), url);
        } catch (RuntimeException e) {
            log.debug("记录上次访问地址失败：{}", e.getMessage());
        }
    }

    /** 把登录态与当前地址落盘；页面内的登录跳转不经过导航，所以关会话与定时都会各存一次。 */
    private void flushState(BrowserSession session) {
        if (session.isClosed()) return;
        try {
            rememberUrl(session, session.currentUrl());
            session.saveStorageState(
                    stateStore.storageFile(session.projectId(), session.systemId(), session.environmentId()));
        } catch (RuntimeException e) {
            log.debug("保存浏览器登录态失败：{}", e.getMessage());
        }
    }

    /** 应用启动后预热浏览器，第一次点“前往”不用再等 Edge 冷启动。 */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (!enabled) return;
        CompletableFuture.runAsync(() -> {
            synchronized (lifecycleLock) {
                try {
                    ensureBrowser();
                    log.info("浏览器已预热，首次打开页面可立即导航");
                } catch (RuntimeException e) {
                    log.warn("浏览器预热失败：{}", e.getMessage());
                }
            }
        });
    }

    public Optional<BrowserSession> find(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public int sessionCount() { return sessions.size(); }

    public void close(String sessionId) {
        BrowserSession session = sessions.remove(sessionId);
        if (session == null) return;
        // 关之前把登录态留下：用户通常在登录后不再手动导航，只在页面里点来点去。
        flushState(session);
        session.close();
    }

    @Scheduled(fixedDelay = 60000)
    public void closeIdleSessions() {
        for (BrowserSession session : sessions.values()) {
            if (session.isIdle(idleTimeout)) {
                close(session.id());
                continue;
            }
            flushState(session);
        }
    }

    private void ensureBrowser() {
        if (browser != null && browser.isConnected()) return;
        closeBrowser();
        playwright = Playwright.create();
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(headless);
        if (channel != null && !channel.isBlank()) options.setChannel(channel);
        if (proxy != null && !proxy.isBlank()) {
            options.setProxy(new com.microsoft.playwright.options.Proxy(proxy.trim()));
        }
        try {
            browser = playwright.chromium().launch(options);
        } catch (PlaywrightException e) {
            playwright.close();
            playwright = null;
            throw new BrowserSessionException("浏览器启动失败，请确认已安装 Edge 或 Chromium", e);
        }
    }

    private static BrowserSessionException friendlyNavigationError(String url, PlaywrightException error) {
        return new BrowserSessionException("无法打开 " + url + "：" + shortMessage(error)
                + "。常见原因：网络或代理不通（可在 qts.env 配 QTS_BROWSER_PROXY）；"
                + "目标站点拦截了无头浏览器（可设 QTS_BROWSER_HEADLESS=false）；"
                + "或页面需要先完成统一认证。", error);
    }

    private static String shortMessage(PlaywrightException error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return "网络请求失败";
        String text;
        int net = message.indexOf("net::");
        if (net >= 0) {
            // Playwright 原文是 "Error { message='net::ERR_... at URL' name='Error stack='..."，
            // 取 net:: 到行尾 / name= 之间的部分就够读了。
            int end = message.indexOf('\n', net);
            text = end > net ? message.substring(net, end) : message.substring(net);
            int name = text.indexOf(" name=");
            if (name > 0) text = text.substring(0, name);
        } else {
            int start = message.indexOf("message='");
            if (start >= 0) {
                int end = message.indexOf('\'', start + 9);
                text = end > start ? message.substring(start + 9, end) : message.substring(start + 9);
            } else {
                text = message;
            }
        }
        text = text.replace("\r", " ").replace("\n", " ").trim();
        if (text.endsWith("'")) text = text.substring(0, text.length() - 1);
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    private static void validateHttpUrl(String url) {
        try {
            URI uri = URI.create(url);
            if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw new BrowserSessionException("只允许访问明确的 HTTP/HTTPS 地址", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        synchronized (lifecycleLock) {
            sessions.keySet().forEach(this::close);
            closeBrowser();
        }
    }

    private void closeBrowser() {
        if (browser != null) { browser.close(); browser = null; }
        if (playwright != null) { playwright.close(); playwright = null; }
    }
}
