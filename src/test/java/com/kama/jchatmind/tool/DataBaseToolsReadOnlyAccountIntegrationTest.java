package com.kama.jchatmind.tool;

import com.kama.jchatmind.agent.tools.DataBaseTools;
import com.kama.jchatmind.agent.tools.SqlSafetyValidator;
import com.kama.jchatmind.config.DatabaseToolDataSourceConfig;
import com.kama.jchatmind.config.DatabaseToolProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "JCHATMIND_DB_READONLY_URL", matches = ".+")
class DataBaseToolsReadOnlyAccountIntegrationTest {

    @Test
    void dataBaseToolsUsesConfiguredReadOnlyAccountAndKeepsValidatorBoundary() {
        new ApplicationContextRunner()
                .withUserConfiguration(DatabaseToolDataSourceConfig.class)
                .withBean(SqlSafetyValidator.class, SqlSafetyValidator::new)
                .withBean(DatabaseToolProperties.class, DatabaseToolProperties::new)
                .withBean(DataBaseTools.class)
                .withPropertyValues(
                        "jchatmind.database-tool.datasource.url=" + getenv("JCHATMIND_DB_READONLY_URL"),
                        "jchatmind.database-tool.datasource.username=" + getenv("JCHATMIND_DB_READONLY_USERNAME"),
                        "jchatmind.database-tool.datasource.password=" + getenv("JCHATMIND_DB_READONLY_PASSWORD"),
                        "jchatmind.database-tool.datasource.driver-class-name=org.postgresql.Driver")
                .run(context -> {
                    DataBaseTools tool = context.getBean(DataBaseTools.class);

                    String selectResult = tool.query("SELECT 1");
                    String rejectedResult = tool.query("UPDATE code_repository SET name = name WHERE false");

                    assertThat(selectResult).contains("Query result:");
                    assertThat(rejectedResult).startsWith("[REJECTED_BY_POLICY]");
                });
    }

    private static String getenv(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value;
    }
}
