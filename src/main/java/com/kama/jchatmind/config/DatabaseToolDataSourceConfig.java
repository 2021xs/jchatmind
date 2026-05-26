package com.kama.jchatmind.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;

@Configuration
@EnableConfigurationProperties(DatabaseToolDataSourceConfig.DatabaseToolDataSourceProperties.class)
public class DatabaseToolDataSourceConfig {

    @Bean(name = "databaseToolDataSource")
    public DataSource databaseToolDataSource(DatabaseToolDataSourceProperties properties) {
        properties.validate();
        return DataSourceBuilder.create()
                .url(properties.getUrl())
                .username(properties.getUsername())
                .password(properties.getPassword())
                .driverClassName(properties.getDriverClassName())
                .build();
    }

    @Bean(name = "databaseToolJdbcTemplate")
    public JdbcTemplate databaseToolJdbcTemplate(
            @Qualifier("databaseToolDataSource") DataSource databaseToolDataSource) {
        return new JdbcTemplate(databaseToolDataSource);
    }

    @Data
    @ConfigurationProperties(prefix = "jchatmind.database-tool.datasource")
    public static class DatabaseToolDataSourceProperties {
        private String url;
        private String username;
        private String password;
        private String driverClassName;

        private void validate() {
            if (!StringUtils.hasText(url)
                    || !StringUtils.hasText(username)
                    || !StringUtils.hasText(password)
                    || !StringUtils.hasText(driverClassName)) {
                throw new IllegalStateException(
                        "jchatmind.database-tool.datasource must be configured with a dedicated read-only database account");
            }
        }
    }
}
