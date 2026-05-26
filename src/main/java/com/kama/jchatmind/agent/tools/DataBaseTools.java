package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.config.DatabaseToolProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class DataBaseTools implements Tool {
    private final JdbcTemplate jdbcTemplate;
    private final SqlSafetyValidator sqlSafetyValidator;
    private final DatabaseToolProperties properties;

    @Autowired
    public DataBaseTools(@Qualifier("databaseToolJdbcTemplate") JdbcTemplate jdbcTemplate,
                         SqlSafetyValidator sqlSafetyValidator,
                         DatabaseToolProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.sqlSafetyValidator = sqlSafetyValidator;
        this.properties = properties;
        log.info("DataBaseTools initialized with databaseToolJdbcTemplate; configure it with a read-only database account");
        this.jdbcTemplate.setQueryTimeout(properties.getQueryTimeoutSeconds());
        this.jdbcTemplate.setMaxRows(properties.getMaxRows());
        this.jdbcTemplate.setFetchSize(properties.getFetchSize());
    }

    public DataBaseTools(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new SqlSafetyValidator(), new DatabaseToolProperties());
    }

    @Override
    public String getName() {
        return "databaseQuery";
    }

    @Override
    public String getDescription() {
        return "Read-only database query tool. Allows one safe SELECT statement only.";
    }

    @Override
    public ToolType getType() {
        return ToolType.OPTIONAL;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "databaseQuery",
            description = "Execute a safe read-only database query. Only one SELECT statement is allowed. Dangerous SQL is rejected by parser policy."
    )
    public String query(String sql) {
        SqlSafetyValidator.SqlValidationResult validation;
        try {
            validation = sqlSafetyValidator.validate(sql, properties.getMaxRows());
        } catch (IllegalArgumentException e) {
            log.warn("Rejected unsafe SQL by parser policy: {}", e.getMessage());
            return "[REJECTED_BY_POLICY] rejected=true reason=" + e.getMessage();
        }

        try {
            List<String> rows = jdbcTemplate.query(validation.executableSql(), (ResultSet rs) -> formatRows(rs));
            String result = "Query result:\n" + String.join("\n", rows);
            return truncate(result);
        } catch (Exception e) {
            log.error("Database query execution failed: {}", e.getMessage(), e);
            throw new IllegalStateException("Database query execution failed: " + e.getMessage(), e);
        }
    }

    private List<String> formatRows(ResultSet rs) throws java.sql.SQLException {
        List<String> resultRows = new ArrayList<>();
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();
        if (columnCount == 0) {
            resultRows.add("(no columns)");
            return resultRows;
        }

        List<String> columnNames = new ArrayList<>();
        for (int i = 1; i <= columnCount; i++) {
            columnNames.add(metaData.getColumnName(i));
        }
        resultRows.add(String.join(" | ", columnNames));
        resultRows.add("-".repeat(Math.max(3, resultRows.get(0).length())));

        int rowCount = 0;
        while (rs.next() && rowCount < properties.getMaxRows()) {
            List<String> values = new ArrayList<>();
            for (int i = 1; i <= columnCount; i++) {
                Object value = rs.getObject(i);
                values.add(value == null ? "NULL" : truncateCell(String.valueOf(value)));
            }
            resultRows.add(String.join(" | ", values));
            rowCount++;
        }
        if (rowCount == 0) {
            resultRows.add("(no rows)");
        }
        return resultRows;
    }

    private String truncate(String value) {
        if (value == null || value.length() <= properties.getMaxResultLength()) {
            return value;
        }
        int keep = Math.max(0, properties.getMaxResultLength() - 32);
        return value.substring(0, keep) + "\n...[truncated]";
    }

    private String truncateCell(String value) {
        if (value == null || value.length() <= properties.getMaxCellChars()) {
            return value;
        }
        int keep = Math.max(0, properties.getMaxCellChars() - 16);
        return value.substring(0, keep) + "...[truncated]";
    }
}
