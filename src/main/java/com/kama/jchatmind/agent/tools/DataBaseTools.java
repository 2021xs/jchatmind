package com.kama.jchatmind.agent.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Slf4j
@Component
public class DataBaseTools implements Tool {
    private static final int QUERY_TIMEOUT_SECONDS = 5;
    private static final int MAX_ROWS = 50;
    private static final int MAX_RESULT_LENGTH = 4000;
    private static final Pattern FORBIDDEN_KEYWORDS = Pattern.compile(
            "\\b(insert|update|delete|drop|alter|truncate|create|grant|revoke|copy|call|do)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern LIMIT_PATTERN = Pattern.compile("\\blimit\\b", Pattern.CASE_INSENSITIVE);

    private final JdbcTemplate jdbcTemplate;

    public DataBaseTools(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcTemplate.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    }

    @Override
    public String getName() {
        return "dataBaseTool";
    }

    @Override
    public String getDescription() {
        return "Read-only PostgreSQL query tool. Allows SELECT and EXPLAIN SELECT only.";
    }

    @Override
    public ToolType getType() {
        return ToolType.OPTIONAL;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "databaseQuery",
            description = "Execute a safe PostgreSQL read query. Only SELECT and EXPLAIN SELECT are allowed. Dangerous SQL is rejected by policy."
    )
    public String query(String sql) {
        SqlPolicyResult policy = validateSql(sql);
        if (!policy.allowed()) {
            log.warn("Rejected unsafe SQL by policy: {}", policy.reason());
            return "[REJECTED_BY_POLICY] rejected=true reason=" + policy.reason();
        }

        try {
            List<String> rows = jdbcTemplate.query(policy.executableSql(), (ResultSet rs) -> formatRows(rs));
            String result = "Query result:\n" + String.join("\n", rows);
            return truncate(result);
        } catch (Exception e) {
            log.error("Database query execution failed: {}", e.getMessage(), e);
            throw new IllegalStateException("Database query execution failed: " + e.getMessage(), e);
        }
    }

    private SqlPolicyResult validateSql(String sql) {
        if (sql == null) {
            return SqlPolicyResult.rejected("sql is empty");
        }
        String trimmed = sql.trim();
        if (trimmed.isEmpty()) {
            return SqlPolicyResult.rejected("sql is empty");
        }
        if (trimmed.contains("--") || trimmed.contains("/*") || trimmed.contains("*/")) {
            return SqlPolicyResult.rejected("sql comments are not allowed");
        }

        String withoutTrailingSemicolon = stripSingleTrailingSemicolon(trimmed);
        if (withoutTrailingSemicolon.contains(";")) {
            return SqlPolicyResult.rejected("multiple SQL statements are not allowed");
        }

        String normalized = withoutTrailingSemicolon
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
        if (FORBIDDEN_KEYWORDS.matcher(normalized).find()) {
            return SqlPolicyResult.rejected("write, ddl, procedure and privileged statements are not allowed");
        }
        if (normalized.startsWith("explain analyze") || normalized.contains(" explain analyze ")) {
            return SqlPolicyResult.rejected("EXPLAIN ANALYZE is not allowed");
        }
        if (normalized.startsWith("explain select")) {
            return SqlPolicyResult.allowed(withoutTrailingSemicolon);
        }
        if (!normalized.startsWith("select")) {
            return SqlPolicyResult.rejected("only SELECT or EXPLAIN SELECT is allowed");
        }

        if (LIMIT_PATTERN.matcher(normalized).find()) {
            return SqlPolicyResult.allowed(withoutTrailingSemicolon);
        }
        String limitedSql = "SELECT * FROM (" + withoutTrailingSemicolon + ") agent_limited_query LIMIT " + MAX_ROWS;
        return SqlPolicyResult.allowed(limitedSql);
    }

    private String stripSingleTrailingSemicolon(String sql) {
        String stripped = sql.trim();
        if (stripped.endsWith(";")) {
            return stripped.substring(0, stripped.length() - 1).trim();
        }
        return stripped;
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
        while (rs.next() && rowCount < MAX_ROWS) {
            List<String> values = new ArrayList<>();
            for (int i = 1; i <= columnCount; i++) {
                Object value = rs.getObject(i);
                values.add(value == null ? "NULL" : String.valueOf(value));
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
        if (value == null || value.length() <= MAX_RESULT_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_RESULT_LENGTH - 32) + "\n...[truncated]";
    }

    private record SqlPolicyResult(boolean allowed, String executableSql, String reason) {
        static SqlPolicyResult allowed(String executableSql) {
            return new SqlPolicyResult(true, executableSql, null);
        }

        static SqlPolicyResult rejected(String reason) {
            return new SqlPolicyResult(false, null, reason);
        }
    }
}
