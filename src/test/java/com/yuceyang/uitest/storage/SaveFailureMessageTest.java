package com.yuceyang.uitest.storage;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 「Unable to commit against JDBC Connection」这类包装错误的真实原因都在最里层，提示必须带出来。 */
class SaveFailureMessageTest {

    @Test
    void deepestCauseIsReportedInsteadOfTheWrapper() {
        RuntimeException wrapper = new RuntimeException("Unable to commit against JDBC Connection",
                new RuntimeException("could not execute statement",
                        new IllegalStateException("ERROR: value too long for type character varying(2000)")));

        assertThat(PostgresProjectStore.rootCause(wrapper))
                .isEqualTo("ERROR: value too long for type character varying(2000)");
    }

    @Test
    void missingMessageFallsBackToTheExceptionType() {
        assertThat(PostgresProjectStore.rootCause(new RuntimeException(new IllegalStateException())))
                .isEqualTo("IllegalStateException");
    }

    @Test
    void onlyTheFirstLineIsKept() {
        RuntimeException error = new RuntimeException("第一行\n第二行");

        assertThat(PostgresProjectStore.rootCause(error)).isEqualTo("第一行");
    }

    @Test
    void veryLongMessagesAreCut() {
        assertThat(PostgresProjectStore.rootCause(new RuntimeException("x".repeat(400)))).endsWith("…");
    }
}
