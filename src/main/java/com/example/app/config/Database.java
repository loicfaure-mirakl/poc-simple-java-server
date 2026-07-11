package com.example.app.config;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Collectors;

public final class Database {

    private Database() {
    }

    public static DSLContext init(String jdbcUrl, String username, String password) {
        DataSource dataSource = dataSource(jdbcUrl, username, password);
        runSchema(dataSource);
        return DSL.using(dataSource, SQLDialect.POSTGRES);
    }

    public static DataSource dataSource(String jdbcUrl, String username, String password) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(jdbcUrl);
        dataSource.setUser(username);
        dataSource.setPassword(password);
        return dataSource;
    }

    private static void runSchema(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(readSchema());
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("Failed to initialize database schema", e);
        }
    }

    private static String readSchema() throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                Database.class.getResourceAsStream("/schema.sql"), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }
}
