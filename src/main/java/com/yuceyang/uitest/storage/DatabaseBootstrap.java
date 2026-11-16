package com.yuceyang.uitest.storage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * 在 PostgreSQL 模式下，如果目标数据库还不存在，先用同一账号连接 maintenance 库并创建它。
 * Flyway 只负责建表，不会创建数据库本身；这一步把“建库”补上。
 */
public final class DatabaseBootstrap implements EnvironmentPostProcessor, Ordered {
    private static final String PREFIX = "jdbc:postgresql://";

    @Override
    public int getOrder() {
        // 必须在 ConfigDataEnvironmentPostProcessor 之后，才能读到 qts.env 与激活的 profile
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty("qts.storage.auto-create-database", Boolean.class, true)) return;
        if (!hasProfile(environment, "postgresql")) return;
        String url;
        String username;
        String password;
        try {
            url = environment.getProperty("spring.datasource.url");
            username = environment.getProperty("spring.datasource.username");
            password = environment.getProperty("spring.datasource.password");
        } catch (RuntimeException e) {
            System.out.println("[QTS] 读取数据库配置失败，跳过自动建库：" + e.getMessage());
            return;
        }
        if (url == null || !url.startsWith(PREFIX) || username == null || password == null) return;
        try {
            ensureDatabase(url, username, password);
        } catch (SQLException e) {
            System.out.println("[QTS] 自动创建数据库失败：" + e.getMessage());
            System.out.println("[QTS] 请用超级用户执行：CREATE DATABASE \"" + databaseName(url)
                    + "\" OWNER \"" + username + "\"; 或给该账号 CREATEDB 权限后重启。");
        }
    }

    static void ensureDatabase(String url, String username, String password) throws SQLException {
        String database = databaseName(url);
        if (database.isBlank()) return;
        String maintenanceUrl = maintenanceUrl(url);
        try (Connection connection = DriverManager.getConnection(maintenanceUrl, username, password)) {
            if (exists(connection, database)) return;
            System.out.println("[QTS] 数据库 " + database + " 不存在，正在创建…");
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE " + quoteIdentifier(database));
            }
            System.out.println("[QTS] 数据库 " + database + " 创建完成，接下来由 Flyway 建表。");
        }
    }

    private static boolean exists(Connection connection, String database) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
            query.setString(1, database);
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    static String databaseName(String url) {
        String rest = url.substring(PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash < 0) return "";
        String tail = rest.substring(slash + 1);
        int query = tail.indexOf('?');
        return query < 0 ? tail : tail.substring(0, query);
    }

    static String maintenanceUrl(String url) {
        String rest = url.substring(PREFIX.length());
        int slash = rest.indexOf('/');
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        int query = url.indexOf('?');
        String params = query < 0 ? "" : url.substring(query);
        return PREFIX + authority + "/postgres" + params;
    }

    static String quoteIdentifier(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private static boolean hasProfile(ConfigurableEnvironment environment, String profile) {
        for (String active : environment.getActiveProfiles()) {
            if (active.toLowerCase(Locale.ROOT).equals(profile)) return true;
        }
        String declared = environment.getProperty("spring.profiles.active", "");
        for (String active : declared.split(",")) {
            if (active.trim().toLowerCase(Locale.ROOT).equals(profile)) return true;
        }
        return false;
    }
}
