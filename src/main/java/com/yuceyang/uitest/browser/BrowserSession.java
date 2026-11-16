package com.yuceyang.uitest.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.yuceyang.uitest.screenshot.ScreenshotBinding;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

public final class BrowserSession implements AutoCloseable {
    private final String id = UUID.randomUUID().toString();
    private final String projectId;
    private final String systemId;
    private final String environmentId;
    private final BrowserContext context;
    private final Page page;
    private final ExecutorService operations;
    private final AtomicLong revision = new AtomicLong();
    private volatile Instant lastAccessedAt = Instant.now();
    private volatile boolean closed;

    BrowserSession(String projectId, String systemId, String environmentId, BrowserContext context) {
        this(projectId, systemId, environmentId, context, null);
    }

    BrowserSession(String projectId, String systemId, String environmentId, BrowserContext context,
                   NetworkLog networkLog) {
        this.projectId = projectId;
        this.systemId = systemId;
        this.environmentId = environmentId;
        this.context = context;
        this.page = context.newPage();
        this.operations = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "qts-browser-" + id);
            thread.setDaemon(true);
            return thread;
        });
        if (networkLog != null) captureNetwork(networkLog);
    }

    /**
     * 记录页面真实发出的接口调用，供用例生成填写「携带参数」。
     * 只记 xhr/fetch，请求体做长度裁剪并抹掉 password/token 一类的值；失败一律吞掉，不能影响浏览。
     */
    private void captureNetwork(NetworkLog networkLog) {
        try {
            page.onResponse(response -> {
                try {
                    var request = response.request();
                    String body;
                    try {
                        body = NetworkExchange.redact(request.postData());
                    } catch (RuntimeException ignored) {
                        body = null;
                    }
                    networkLog.record(new NetworkExchange(id, request.method(), request.url(),
                            request.resourceType(), response.status(), body, System.currentTimeMillis()));
                } catch (RuntimeException ignored) {
                    // 单条请求记录失败不影响会话
                }
            });
        } catch (RuntimeException e) {
            // 某些浏览器通道不支持事件监听时，退化为「没有接口样本」，用例仍可生成
        }
    }

    public String id() { return id; }
    public String projectId() { return projectId; }
    public String systemId() { return systemId; }
    public String environmentId() { return environmentId; }
    public String pageRevision() { return Long.toString(revision.get()); }
    public Instant lastAccessedAt() { return lastAccessedAt; }
    public boolean isIdle(Duration timeout) { return lastAccessedAt.isBefore(Instant.now().minus(timeout)); }
    public boolean isClosed() { return closed; }

    public <T> T execute(Callable<T> operation, Duration timeout) {
        return execute(operation, timeout, true);
    }

    /** 读取页面状态（镜像、元素扫描、截图）不改变页面版本。 */
    public <T> T read(Callable<T> operation, Duration timeout) {
        return execute(operation, timeout, false);
    }

    private <T> T execute(Callable<T> operation, Duration timeout, boolean advanceRevision) {
        if (closed) throw new IllegalStateException("浏览器会话已关闭");
        lastAccessedAt = Instant.now();
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    T result = operation.call();
                    if (advanceRevision) revision.incrementAndGet();
                    lastAccessedAt = Instant.now();
                    return result;
                } catch (Exception e) {
                    if (e instanceof RuntimeException runtimeException) throw runtimeException;
                    throw new BrowserSessionException("浏览器操作失败", e);
                }
            }, operations).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new BrowserSessionException("浏览器操作超时，未执行后续自动截图或绑定", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            throw new BrowserSessionException("浏览器操作失败", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrowserSessionException("浏览器操作被中断", e);
        }
    }

    public Page page() { return page; }
    /** 当前地址（登录跳转后的真实地址，不一定是用户在地址栏输入的那个）。 */
    public String currentUrl() {
        return read(() -> page.url(), Duration.ofSeconds(10));
    }

    /**
     * 把整份登录态（Cookie、localStorage、IndexedDB）写到 file。
     * 先写临时文件再替换，避免进程中途退出留下半个 JSON 导致下次启动读不出来。
     */
    void saveStorageState(Path file) {
        execute(() -> {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            context.storageState(new BrowserContext.StorageStateOptions()
                    .setPath(temporary)
                    .setIndexedDB(true));
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            return Boolean.TRUE;
        }, Duration.ofSeconds(20), false);
    }

    public ScreenshotBinding binding() {
        return new ScreenshotBinding(projectId, systemId, environmentId, id, pageRevision());
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        operations.shutdown();
        try {
            if (!operations.awaitTermination(10, TimeUnit.SECONDS)) operations.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            operations.shutdownNow();
        }
        context.close();
    }
}
