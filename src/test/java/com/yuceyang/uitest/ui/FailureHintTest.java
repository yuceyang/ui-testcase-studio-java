package com.yuceyang.uitest.ui;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 提示里要带上最里层原因和一句处置建议，用户照着就能判断是不是数据问题。 */
class FailureHintTest {

    @Test
    void columnOverflowIsExplained() {
        RuntimeException error = new RuntimeException("Unable to commit against JDBC Connection",
                new IllegalStateException("ERROR: value too long for type character varying(2000)"));

        String message = WorkspaceView.describeFailure(error);

        assertThat(message).contains("value too long").contains("列长度");
    }

    @Test
    void droppedConnectionSuggestsRetry() {
        String message = WorkspaceView.describeFailure(new RuntimeException("Could not open JDBC Connection for transaction"));

        assertThat(message).contains("连接已断").contains("重试");
    }

    @Test
    void unknownErrorsKeepTheOriginalMessage() {
        String message = WorkspaceView.describeFailure(new RuntimeException("项目不存在：p1"));

        assertThat(message).isEqualTo("项目不存在：p1");
    }
}
