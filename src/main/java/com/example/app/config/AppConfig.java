package com.example.app.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

public final class AppConfig {

    private static final Properties PROPERTIES = load();

    private AppConfig() {
    }

    public static String dbUrl() {
        return get("db.url");
    }

    public static String dbUser() {
        return get("db.user");
    }

    public static String dbPassword() {
        return get("db.password");
    }

    public static int serverPort() {
        return Integer.parseInt(get("server.port"));
    }

    private static String get(String key) {
        return System.getProperty(key, PROPERTIES.getProperty(key));
    }

    private static Properties load() {
        Properties properties = new Properties();
        try (InputStream in = AppConfig.class.getResourceAsStream("/application.properties")) {
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load application.properties", e);
        }
        return properties;
    }
}
