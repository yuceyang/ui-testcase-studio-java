package com.yuceyang.uitest.ui;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 关页、刷新、断网时 Tomcat 往已断的连接写响应会抛 ClientAbortException，
 * 这属于「浏览器走了」而不是「操作失败」，不能按 ERROR 记堆栈、更不该弹提示。
 */
class ClientAbortTest {

    @Test
    void clientAbortIsNotABusinessFailure() {
        ClientAbortException error = new ClientAbortException(
                new IOException("你的主机中的软件中止了一个已建立的连接。"));

        assertThat(WorkspaceView.isClientGone(error)).isTrue();
    }

    @Test
    void wrappedIoFailureFromAClosedSocketCountsToo() {
        RuntimeException wrapped = new RuntimeException("界面回推失败",
                new IOException("Connection reset by peer"));

        assertThat(WorkspaceView.isClientGone(wrapped)).isTrue();
        assertThat(WorkspaceView.isClientGone(new ClosedChannelException())).isTrue();
    }

    @Test
    void realBusinessFailuresStillSurface() {
        assertThat(WorkspaceView.isClientGone(new RuntimeException("保存失败：value too long for type character varying(2000)")))
                .isFalse();
        assertThat(WorkspaceView.isClientGone(new NullPointerException("x"))).isFalse();
    }

    @Test
    void selfReferencingCauseChainDoesNotHang() {
        RuntimeException loop = new RuntimeException("loop") {
            @Override public synchronized Throwable getCause() { return this; }
        };

        assertThat(WorkspaceView.isClientGone(loop)).isFalse();
    }
}
