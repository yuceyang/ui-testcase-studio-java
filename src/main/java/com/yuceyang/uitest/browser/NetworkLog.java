package com.yuceyang.uitest.browser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.springframework.stereotype.Component;

/**
 * 每个浏览器会话最近捕获到的接口调用（内存里，不落库、不写日志）。
 * 用例生成用它给「携带参数 / 测试数据」填真实字段与真实值；
 * 会话关闭后仍保留一段时间，因为用户常常是先浏览、后回工作台生成用例。
 */
@Component
public class NetworkLog {
    private static final int MAX_PER_SESSION = 400;
    /** 长期不用关会话的机器上也不至于无限攒：只留最近活跃的若干会话。 */
    private static final int MAX_SESSIONS = 128;

    private final Map<String, Deque<NetworkExchange>> bySession = new ConcurrentHashMap<>();

    public void record(NetworkExchange exchange) {
        if (exchange == null || exchange.sessionId() == null) return;
        if (!exchange.isApiCall() || exchange.isStatic()) return;
        Deque<NetworkExchange> calls = bySession.computeIfAbsent(exchange.sessionId(),
                key -> new ConcurrentLinkedDeque<>());
        calls.addLast(exchange);
        while (calls.size() > MAX_PER_SESSION) calls.pollFirst();
        if (bySession.size() > MAX_SESSIONS) evictIdlestSession();
    }

    private void evictIdlestSession() {
        String idlest = null;
        long idlestAt = Long.MAX_VALUE;
        for (Map.Entry<String, Deque<NetworkExchange>> entry : bySession.entrySet()) {
            NetworkExchange last = entry.getValue().peekLast();
            long at = last == null ? 0L : last.atEpochMs();
            if (at < idlestAt) {
                idlestAt = at;
                idlest = entry.getKey();
            }
        }
        if (idlest != null) bySession.remove(idlest);
    }

    /**
     * 取最近 limit 条接口调用（新的在前）。同一接口的重复请求只留最后一次：
     * 轮询、滚动加载会把真正有参数的调用挤出窗口。
     */
    public List<NetworkExchange> apiCalls(String sessionId, int limit) {
        if (sessionId == null || limit <= 0) return List.of();
        Deque<NetworkExchange> calls = bySession.get(sessionId);
        if (calls == null || calls.isEmpty()) return List.of();
        List<NetworkExchange> newestFirst = new ArrayList<>(calls);
        Collections.reverse(newestFirst);
        Set<String> seen = new HashSet<>();
        List<NetworkExchange> out = new ArrayList<>();
        for (NetworkExchange exchange : newestFirst) {
            if (!seen.add(exchange.key())) continue;
            out.add(exchange);
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** 只保留目标站点自己的接口，第三方统计/埋点不要混进提示词。 */
    public List<NetworkExchange> apiCalls(String sessionId, int limit, String hostHint) {
        List<NetworkExchange> calls = apiCalls(sessionId, Math.max(limit * 4, limit));
        if (hostHint == null || hostHint.isBlank()) {
            return calls.size() > limit ? calls.subList(0, limit) : calls;
        }
        List<NetworkExchange> matched = new ArrayList<>();
        List<NetworkExchange> others = new ArrayList<>();
        for (NetworkExchange call : calls) {
            if (call.url() != null && call.url().toLowerCase().contains(hostHint.toLowerCase())) matched.add(call);
            else others.add(call);
        }
        matched.addAll(others);
        return matched.size() > limit ? matched.subList(0, limit) : matched;
    }

    public int size(String sessionId) {
        Deque<NetworkExchange> calls = bySession.get(sessionId);
        return calls == null ? 0 : calls.size();
    }

    public void forget(String sessionId) {
        bySession.remove(sessionId);
    }
}
