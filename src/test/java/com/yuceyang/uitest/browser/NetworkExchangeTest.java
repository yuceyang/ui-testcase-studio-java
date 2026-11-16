package com.yuceyang.uitest.browser;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class NetworkExchangeTest {

    @Test
    void splitsQueryAndJsonBodyIntoRealParams() {
        NetworkExchange call = new NetworkExchange("s", "POST",
                "http://demo/api/projects?projectCode=123&page=1", "xhr", 200,
                "{\"name\":\"自动化测试_001\",\"workerGroup\":\"default\"}", 0L);

        assertThat(call.params()).contains(
                "projectCode=123",
                "page=1",
                "name=自动化测试_001",
                "workerGroup=default");
        assertThat(call.describe()).startsWith("POST http://demo/api/projects").contains("→ 200");
    }

    @Test
    void formBodiesAndNestedJsonAreFlattened() {
        NetworkExchange form = new NetworkExchange("s", "POST", "http://demo/login", "xhr", 200,
                "userName=admin&userPassword=s3cr3t-value", 0L);
        assertThat(form.params()).contains("userName=admin");
        // 凭据的值要抹掉，字段名保留，后续还能照着写参数名
        assertThat(form.params()).contains("userPassword=***").doesNotContain("s3cr3t-value");

        NetworkExchange nested = new NetworkExchange("s", "POST", "http://demo/api/task", "fetch", 200,
                "{\"task\":{\"name\":\"t1\",\"cron\":\"0 0 * * * ?\"},\"ids\":[1,2]}", 0L);
        assertThat(nested.params()).contains("task.name=t1", "task.cron=0 0 * * * ?", "ids[0]=1", "ids[1]=2");
    }

    @Test
    void onlyApiCallsAreKeptAndDuplicatesCollapse() {
        NetworkLog log = new NetworkLog();
        log.record(new NetworkExchange("s", "GET", "http://demo/app.js", "script", 200, null, 0L));
        log.record(new NetworkExchange("s", "GET", "http://demo/api/users?page=1", "xhr", 200, null, 1L));
        log.record(new NetworkExchange("s", "GET", "http://demo/api/users?page=1", "xhr", 200, null, 2L));
        log.record(new NetworkExchange("s", "POST", "http://demo/api/users", "xhr", 201, "{\"a\":1}", 3L));

        List<NetworkExchange> calls = log.apiCalls("s", 10);
        assertThat(calls).hasSize(2);
        assertThat(calls.getFirst().url()).isEqualTo("http://demo/api/users"); // 新的在前
        assertThat(log.apiCalls("nope", 10)).isEmpty();
    }

    @Test
    void idleSessionsAreEvictedSoTheBufferStaysBounded() {
        NetworkLog log = new NetworkLog();
        for (int i = 0; i < 200; i++) {
            log.record(new NetworkExchange("s" + i, "GET", "http://demo/api/users?page=" + i,
                    "xhr", 200, null, 1_000L + i));
        }
        assertThat(log.size("s199")).isEqualTo(1);
        assertThat(log.size("s0")).isZero();
    }

    @Test
    void hostHintKeepsTargetSiteCallsFirst() {
        NetworkLog log = new NetworkLog();
        log.record(new NetworkExchange("s", "POST", "https://analytics.example/track", "xhr", 200, "{\"e\":1}", 1L));
        log.record(new NetworkExchange("s", "GET", "http://demo/api/users?page=1", "xhr", 200, null, 2L));

        List<NetworkExchange> calls = log.apiCalls("s", 1, "demo");
        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst().url()).contains("demo/api/users");
    }
}
