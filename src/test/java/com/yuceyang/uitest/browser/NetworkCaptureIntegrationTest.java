package com.yuceyang.uitest.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitUntilState;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实浏览器 + 真实 HTTP 服务，验证「页面发出的接口请求确实被记进 NetworkLog」。
 * 这是「携带参数必须来自真实接口」这条链路里唯一没被单测覆盖的一环（Playwright 事件注册与派发）。
 */
class NetworkCaptureIntegrationTest {

    @Test
    void requestsFiredByThePageEndUpInTheNetworkLog() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String html = "<!doctype html><html><head><meta charset='utf-8'><title>查询页</title></head>"
                    + "<body><div>项目列表</div><script>"
                    + "fetch('/api/users?page=1&pageSize=20');"
                    + "fetch('/api/projects',{method:'POST',"
                    + "headers:{'Content-Type':'application/json'},"
                    + "body:JSON.stringify({projectCode:'ds-001',workerGroup:'default',"
                    + "task:{name:'t1',cron:'0 0 * * * ?'}})});"
                    + "fetch('/api/login',{method:'POST',"
                    + "headers:{'Content-Type':'application/x-www-form-urlencoded'},"
                    + "body:'userName=admin&userPassword=s3cr3t-value'});"
                    + "</script></body></html>";
            respond(exchange, html, "text/html; charset=utf-8");
        });
        server.createContext("/api/users", exchange -> respond(exchange, "{\"total\":1}", "application/json"));
        server.createContext("/api/projects", exchange -> respond(exchange, "{\"code\":0}", "application/json"));
        server.createContext("/api/login", exchange -> respond(exchange, "{\"code\":0}", "application/json"));
        server.start();

        NetworkLog log = new NetworkLog();
        try (Playwright playwright = Playwright.create()) {
            Browser browser = launchBrowserOrSkip(playwright);
            BrowserSession session = new BrowserSession("p", "s", "e", browser.newContext(), log);
            try {
                String home = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
                session.execute(() -> session.page().navigate(home,
                        new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD).setTimeout(30000)),
                        Duration.ofSeconds(30));

                List<NetworkExchange> calls = awaitCalls(session, log, 3);
                assertThat(calls).extracting(NetworkExchange::url)
                        .anySatisfy(url -> assertThat(url).contains("/api/users").contains("pageSize=20"));

                NetworkExchange projects = firstOf(calls, "/api/projects");
                assertThat(projects.method()).isEqualTo("POST");
                assertThat(projects.resourceType()).isEqualTo("fetch");
                assertThat(projects.params()).contains(
                        "projectCode=ds-001", "workerGroup=default", "task.name=t1",
                        "task.cron=0 0 * * * ?");

                // 真实请求体里的口令必须在入库前就被抹掉，字段名仍然留着
                NetworkExchange login = firstOf(calls, "/api/login");
                assertThat(login.params()).contains("userName=admin", "userPassword=***")
                        .doesNotContain("s3cr3t-value");
            } finally {
                session.close();
                browser.close();
            }
        } finally {
            server.stop(0);
        }
    }

    private static Browser launchBrowserOrSkip(Playwright playwright) {
        try {
            return playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(true).setChannel("msedge"));
        } catch (PlaywrightException e) {
            Assumptions.assumeTrue(false, "本机未安装 Edge，跳过真实浏览器抓包用例：" + e.getMessage());
            throw e;
        }
    }

    /**
     * Playwright 的响应事件在驱动侧派发，只在 Java 线程里 sleep 收不到；
     * 每轮轮询夹一次 waitForTimeout，事件才会落到 NetworkLog。
     */
    private static List<NetworkExchange> awaitCalls(BrowserSession session, NetworkLog log, int expected) {
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            if (log.apiCalls(session.id(), 20).size() >= expected) break;
            session.read(() -> {
                session.page().waitForTimeout(200);
                return null;
            }, Duration.ofSeconds(10));
        }
        return log.apiCalls(session.id(), 20);
    }

    private static NetworkExchange firstOf(List<NetworkExchange> calls, String path) {
        return calls.stream().filter(c -> c.url().contains(path)).findFirst().orElseThrow(
                () -> new AssertionError("没有抓到 " + path + "，实际：" + calls.stream()
                        .map(NetworkExchange::url).toList()));
    }

    private static void respond(HttpExchange exchange, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
