package com.kama.jchatmind.tool;

import com.kama.jchatmind.agent.tools.DataBaseTools;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolSafetyPolicyTest {

    @Test
    void registryShouldRejectUnknownDisabledAndUnauthorizedTools() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();

        assertFalse(registry.canExposeToAgent("notExistingTool"));
        assertFalse(registry.isAllowedForRuntime("databaseQuery", List.of("searchProjectCode")));

        assertTrue(registry.canExposeToAgent("databaseQuery"));
        assertTrue(registry.isAllowedForRuntime("dataBaseTool", List.of("databaseQuery")));
        assertEquals("databaseQuery", registry.canonicalName("dataBaseTool"));
    }

    @Test
    void databaseToolShouldAllowSelectWithParserLimit() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(eq("SELECT 1 LIMIT 50"), any(ResultSetExtractor.class)))
                .thenReturn(List.of("one", "---", "1"));

        DataBaseTools tool = new DataBaseTools(jdbcTemplate);

        String selectResult = tool.query("SELECT 1");

        assertTrue(selectResult.contains("Query result:"));
        verify(jdbcTemplate).setQueryTimeout(5);
        verify(jdbcTemplate).setMaxRows(50);
        verify(jdbcTemplate).setFetchSize(50);
        verify(jdbcTemplate).query(eq("SELECT 1 LIMIT 50"), any(ResultSetExtractor.class));
    }

    @Test
    void databaseToolShouldRejectDangerousSqlBeforeJdbcExecution() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DataBaseTools tool = new DataBaseTools(jdbcTemplate);

        List<String> rejectedSql = List.of(
                "UPDATE users SET name='x'",
                "DELETE FROM users",
                "DROP TABLE users",
                "ALTER TABLE users ADD COLUMN age int",
                "INSERT INTO users(id) VALUES (1)",
                "SELECT * FROM a; DROP TABLE b",
                "CALL refresh_user_stats()",
                "SELECT FROM WHERE",
                "SELECT * INTO OUTFILE '/tmp/users.csv' FROM users",
                "EXPLAIN ANALYZE SELECT * FROM users"
        );

        for (String sql : rejectedSql) {
            String result = tool.query(sql);
            assertTrue(result.startsWith("[REJECTED_BY_POLICY]"), sql);
        }
        verify(jdbcTemplate, never()).query(any(String.class), any(ResultSetExtractor.class));
    }
}
