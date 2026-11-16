package com.yuceyang.uitest.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseBootstrapTest {
    @Test
    void extractsDatabaseNameFromJdbcUrl() {
        assertThat(DatabaseBootstrap.databaseName("jdbc:postgresql://127.0.0.1:5432/ui_testcase_studio"))
                .isEqualTo("ui_testcase_studio");
        assertThat(DatabaseBootstrap.databaseName("jdbc:postgresql://db.local:5432/qts?sslmode=disable"))
                .isEqualTo("qts");
        assertThat(DatabaseBootstrap.databaseName("jdbc:postgresql://127.0.0.1:5432/")).isEmpty();
    }

    @Test
    void buildsMaintenanceUrlKeepingHostAndParameters() {
        assertThat(DatabaseBootstrap.maintenanceUrl("jdbc:postgresql://127.0.0.1:5432/ui_testcase_studio"))
                .isEqualTo("jdbc:postgresql://127.0.0.1:5432/postgres");
        assertThat(DatabaseBootstrap.maintenanceUrl("jdbc:postgresql://db.local:5433/qts?sslmode=disable"))
                .isEqualTo("jdbc:postgresql://db.local:5433/postgres?sslmode=disable");
    }

    @Test
    void quotesDatabaseIdentifier() {
        assertThat(DatabaseBootstrap.quoteIdentifier("ui_testcase_studio"))
                .isEqualTo("\"ui_testcase_studio\"");
        assertThat(DatabaseBootstrap.quoteIdentifier("weird\"name"))
                .isEqualTo("\"weird\"\"name\"");
    }
}
