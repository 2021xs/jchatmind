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
        this.jdbcTemplate.setMaxRows(probeLimit());
        this.jdbcTemplate.setFetchSize(properties.getFetchSize());
    }

    public DataBaseTools(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new SqlSafetyValidator(), new DatabaseToolProperties());
    }

    @Override
    public ToolType getType() {
        return ToolType.OPTIONAL;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "databaseQuery",
            description = """
                    Execute a safe read-only query against PostgreSQL. Only one SELECT statement is allowed.
                    Use PostgreSQL syntax. Do not use MySQL-only SQL such as SHOW TABLES, DATABASE(), COLUMN_TYPE, or COLUMN_COMMENT.
                    To inspect tables, query information_schema.tables or information_schema.columns with table_schema='public'.
                    Use this tool only when the user explicitly asks about database schema, fields, records, or SQL query results,
                    or when code evidence cannot explain persistence behavior. For code execution flow analysis, prefer searchProjectCode.
                    If hasMore=true, the result is partial; narrow or aggregate the query when complete coverage is required.
                    Dangerous SQL is rejected by parser policy.
                    """
    )
    public String query(String sql) {
        SqlSafetyValidator.SqlValidationResult validation;
        try {
            validation = sqlSafetyValidator.validate(sql, probeLimit());
        } catch (IllegalArgumentException e) {
            log.warn("Rejected unsafe SQL by parser policy: {}", e.getMessage());
            return "[REJECTED_BY_POLICY] rejected=true reason=" + e.getMessage();
        }

        try {
            List<String> rows = jdbcTemplate.query(validation.executableSql(), (ResultSet rs) -> formatRows(rs));
            return "Query result:\n" + String.join("\n", rows);
        } catch (Exception e) {
            log.error("Database query execution failed: {}", e.getMessage(), e);
            throw new IllegalStateException("Database query execution failed: " + e.getMessage(), e);
        }
    }

    private List<String> formatRows(ResultSet rs) throws java.sql.SQLException {
        List<String> resultRows = new ArrayList<>();
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();

        List<String> columnNames = new ArrayList<>();
        for (int i = 1; i <= columnCount; i++) {
            columnNames.add(metaData.getColumnName(i));
        }

        List<String> dataRows = new ArrayList<>();
        int probeLimit = probeLimit();
        while (dataRows.size() < probeLimit && rs.next()) {
            List<String> values = new ArrayList<>();
            for (int i = 1; i <= columnCount; i++) {
                Object value = rs.getObject(i);
                values.add(value == null ? "NULL" : truncateCell(String.valueOf(value)));
            }
            dataRows.add(String.join(" | ", values));
        }

        boolean hasMore = dataRows.size() > properties.getMaxRows();
        if (hasMore) {
            dataRows = new ArrayList<>(dataRows.subList(0, properties.getMaxRows()));
        }

        resultRows.add("status: " + (hasMore ? "PARTIAL" : "SUCCESS"));
        resultRows.add("completeness: " + (hasMore ? "PARTIAL" : "COMPLETE"));
        resultRows.add("rowsReturned: " + dataRows.size());
        resultRows.add("rowLimit: " + properties.getMaxRows());
        resultRows.add("hasMore: " + hasMore);
        if (hasMore) {
            resultRows.add("");
            resultRows.add("message:");
            resultRows.add("More rows exist. The rows below are a bounded partial result.");
            resultRows.add("Do not treat them as the complete query result.");
        }
        resultRows.add("");
        resultRows.add("columns:");
        resultRows.add(columnNames.isEmpty() ? "(no columns)" : String.join(" | ", columnNames));
        resultRows.add("");
        resultRows.add("rows:");
        resultRows.addAll(dataRows.isEmpty() ? List.of("(no rows)") : dataRows);
        return resultRows;
    }

    private int probeLimit() {
        int rowLimit = properties.getMaxRows();
        if (rowLimit <= 0 || rowLimit == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maxRows must be between 1 and " + (Integer.MAX_VALUE - 1));
        }
        return rowLimit + 1;
    }

    private String truncateCell(String value) {
        if (value == null || value.length() <= properties.getMaxCellChars()) {
            return value;
        }
        String marker = "...[TRUNCATED_CELL]";
        int keep = Math.max(0, properties.getMaxCellChars() - marker.length());
        return value.substring(0, keep) + marker;
    }
}
