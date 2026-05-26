package com.kama.jchatmind.config;

import com.kama.jchatmind.agent.tools.DataBaseTools;
import com.kama.jchatmind.agent.tools.SqlSafetyValidator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseToolDataSourceConfigTest {

    @Test
    void createsDedicatedDatabaseToolDataSourceAndJdbcTemplate() {
        new ApplicationContextRunner()
                .withUserConfiguration(DatabaseToolDataSourceConfig.class)
                .withPropertyValues(
                        "jchatmind.database-tool.datasource.url=jdbc:postgresql://localhost:5432/jchatmind",
                        "jchatmind.database-tool.datasource.username=jchatmind_readonly",
                        "jchatmind.database-tool.datasource.password=test-password",
                        "jchatmind.database-tool.datasource.driver-class-name=org.postgresql.Driver")
                .run(context -> {
                    assertThat(context).hasBean("databaseToolDataSource");
                    assertThat(context).hasBean("databaseToolJdbcTemplate");
                    assertThat(context.getBean("databaseToolDataSource", DataSource.class)).isNotNull();
                    assertThat(context.getBean("databaseToolJdbcTemplate", JdbcTemplate.class)).isNotNull();
                });
    }

    @Test
    void dataBaseToolsUsesQualifiedReadOnlyJdbcTemplate() {
        JdbcTemplate defaultJdbcTemplate = mock(JdbcTemplate.class);
        JdbcTemplate readOnlyJdbcTemplate = mock(JdbcTemplate.class);
        when(readOnlyJdbcTemplate.query(eq("SELECT 1 LIMIT 50"), any(ResultSetExtractor.class)))
                .thenReturn(List.of("one", "---", "1"));

        new ApplicationContextRunner()
                .withBean("jdbcTemplate", JdbcTemplate.class, () -> defaultJdbcTemplate)
                .withBean("databaseToolJdbcTemplate", JdbcTemplate.class, () -> readOnlyJdbcTemplate)
                .withBean(SqlSafetyValidator.class, SqlSafetyValidator::new)
                .withBean(DatabaseToolProperties.class, DatabaseToolProperties::new)
                .withBean(DataBaseTools.class)
                .run(context -> {
                    DataBaseTools tool = context.getBean(DataBaseTools.class);

                    String result = tool.query("SELECT 1");

                    assertThat(result).contains("Query result:");
                    verify(readOnlyJdbcTemplate).setQueryTimeout(5);
                    verify(readOnlyJdbcTemplate).setMaxRows(50);
                    verify(readOnlyJdbcTemplate).setFetchSize(50);
                    verify(readOnlyJdbcTemplate).query(eq("SELECT 1 LIMIT 50"), any(ResultSetExtractor.class));
                    verify(defaultJdbcTemplate, never()).query(any(String.class), any(ResultSetExtractor.class));
                });
    }
}
