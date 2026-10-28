package com.example.loancollections.repository.jdbc;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Repository;

/** Bulk operations on the whole dataset (seed load and row counts). */
@Repository
public class DatasetRepository {
    private static final List<String> COUNTED_TABLES = List.of("loans", "installments", "payments", "collection_cases",
            "contact_attempts", "promises_to_pay");
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public DatasetRepository(DataSource dataSource, JdbcTemplate jdbc) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }

    /** Runs the script on one connection, so session settings such as FOREIGN_KEY_CHECKS apply throughout. */
    public void executeScript(Path script) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(script));
        }
    }

    public Map<String, Long> rowCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : COUNTED_TABLES) counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
        return counts;
    }
}