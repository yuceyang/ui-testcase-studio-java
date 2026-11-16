package com.yuceyang.uitest.browser;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实浏览器验证「登录一次、重启免登录」：登录页写下的 Cookie 与 localStorage，
 * 关会话后会落盘，下次同一个系统/环境新开会话直接带着它。
 */
class BrowserStatePersistenceIntegrationTest {

    @Test
    void reopensTheSameEnvironmentAlreadyLoggedIn(@TempDir Path dir) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "qts_session=ok; Path=/");
            respond(exchange, "<!doctype html><meta charset='utf-8'><title>登录</title>"
                    + "<script>localStorage.setItem('qts_token','abc123')</script>已登录", "text/html; charset=utf-8");
        });
        server.createContext("/home", exchange -> respond(exchange,
                "<!doctype html><meta charset='utf-8'><title>首页</title>"
                        + "<div id='who'></div><script>"
                        + "document.getElementById('who').textContent = document.cookie + '|' + localStorage.getItem('qts_token');"
                        + "</script>", "text/html; charset=utf-8"));
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        BrowserStateStore store = new BrowserStateStore(dir.toString());
        BrowserSessionManager manager = new BrowserSessionManager(true, "msedge", true,
                Duration.ofMinutes(10), new NetworkLog(), store);
        try {
            BrowserSession first;
            try {
                first = manager.open("p", "s", "e", base + "/login");
            } catch (BrowserSessionException e) {
                Assumptions.assumeTrue(false, "本机未安装 Edge，跳过真实浏览器免登录用例：" + e.getMessage());
                return;
            }
            assertThat(manager.lastUrl("p", "s", "e")).hasValueSatisfying(
                    url -> assertThat(url).contains("/login"));
            manager.close(first.id());

            Path state = new BrowserStateStore(dir.toString()).storageFile("p", "s", "e");
            assertThat(Files.isRegularFile(state)).isTrue();

            BrowserSession second = manager.open("p", "s", "e", base + "/home");
            try {
                String who = second.read(() -> second.page().textContent("#who"), Duration.ofSeconds(10));
                assertThat(who).contains("qts_session=ok").contains("abc123");
            } finally {
                manager.close(second.id());
            }
            assertThat(manager.lastUrl("p", "s", "e")).hasValueSatisfying(
                    url -> assertThat(url).contains("/home"));
        } finally {
            manager.shutdown();
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
