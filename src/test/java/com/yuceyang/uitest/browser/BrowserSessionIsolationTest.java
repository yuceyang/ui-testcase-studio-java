package com.yuceyang.uitest.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BrowserSessionIsolationTest {
    @Test
    void separateContextsAndPagesAreUsedForTwoUsers() {
        BrowserContext contextA = mock(BrowserContext.class);
        BrowserContext contextB = mock(BrowserContext.class);
        Page pageA = mock(Page.class);
        Page pageB = mock(Page.class);
        when(contextA.newPage()).thenReturn(pageA);
        when(contextB.newPage()).thenReturn(pageB);
        BrowserSession first = new BrowserSession("p", "s1", "e", contextA);
        BrowserSession second = new BrowserSession("p", "s2", "e", contextB);
        try {
            assertThat(first.page()).isSameAs(pageA).isNotSameAs(second.page());
            assertThat(first.binding().sessionId()).isNotEqualTo(second.binding().sessionId());
        } finally {
            first.close();
            second.close();
        }
    }

    @Test
    void readOperationsDoNotChangePageRevision() {
        BrowserContext context = mock(BrowserContext.class);
        Page page = mock(Page.class);
        when(context.newPage()).thenReturn(page);
        BrowserSession session = new BrowserSession("p", "s", "e", context);
        try {
            assertThat(session.execute(() -> "navigate", Duration.ofSeconds(2))).isEqualTo("navigate");
            String afterNavigation = session.pageRevision();
            assertThat(session.read(() -> "mirror", Duration.ofSeconds(2))).isEqualTo("mirror");
            assertThat(session.read(() -> "screenshot", Duration.ofSeconds(2))).isEqualTo("screenshot");
            assertThat(session.pageRevision()).isEqualTo(afterNavigation);
            session.execute(() -> "click", Duration.ofSeconds(2));
            assertThat(session.pageRevision()).isNotEqualTo(afterNavigation);
        } finally {
            session.close();
        }
    }

    @Test
    void operationsWithinOneSessionAreSerialAcrossCallers() throws Exception {
        BrowserContext context = mock(BrowserContext.class);
        Page page = mock(Page.class);
        when(context.newPage()).thenReturn(page);
        BrowserSession session = new BrowserSession("p", "s", "e", context);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch firstEntered = new CountDownLatch(1);
        try {
            Thread callerA = new Thread(() -> session.execute(() -> {
                order.add("enter-a");
                firstEntered.countDown();
                Thread.sleep(100);
                order.add("leave-a");
                return null;
            }, Duration.ofSeconds(2)));
            Thread callerB = new Thread(() -> {
                try { firstEntered.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                session.execute(() -> { order.add("b"); return null; }, Duration.ofSeconds(2));
            });
            callerA.start();
            callerB.start();
            callerA.join(2000);
            callerB.join(2000);
            assertThat(order).containsExactly("enter-a", "leave-a", "b");
        } finally {
            session.close();
        }
    }
}
