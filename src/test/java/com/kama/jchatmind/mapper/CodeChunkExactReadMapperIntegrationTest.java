package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.dto.CodeChunkExactReadResult;
import com.kama.jchatmind.typehandler.PgVectorTypeHandler;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringJUnitConfig(CodeChunkExactReadMapperIntegrationTest.Config.class)
class CodeChunkExactReadMapperIntegrationTest {
    private static final String R1 = "11111111-1111-1111-1111-111111111111";
    private static final String R2 = "22222222-2222-2222-2222-222222222222";
    private static final String F1 = "33333333-3333-3333-3333-333333333333";
    private static final String C1 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String C2 = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @org.springframework.beans.factory.annotation.Autowired
    private CodeChunkMapper mapper;

    @org.springframework.beans.factory.annotation.Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUpSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS code_file (
                    id uuid PRIMARY KEY,
                    repo_id uuid NOT NULL,
                    file_path text NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS code_chunk (
                    id uuid PRIMARY KEY,
                    repo_id uuid NOT NULL,
                    file_id uuid NOT NULL,
                    symbol_name text,
                    chunk_type varchar(64),
                    start_line integer,
                    end_line integer,
                    content text NOT NULL
                )
                """);
        jdbc.execute("TRUNCATE TABLE code_chunk, code_file");
        jdbc.update("INSERT INTO code_file (id, repo_id, file_path) VALUES (CAST(? AS uuid), CAST(? AS uuid), ?)",
                F1, R1, "src/main/java/example/Same.java");
        jdbc.update("""
                        INSERT INTO code_chunk
                        (id, repo_id, file_id, symbol_name, chunk_type, start_line, end_line, content)
                        VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, ?, ?)
                        """,
                C1, R1, F1, "Same#first", "SERVICE_METHOD", 10, 20,
                "CONTENT_ONE_" + "x".repeat(12_000) + "_EXACT_TAIL");
        jdbc.update("""
                        INSERT INTO code_chunk
                        (id, repo_id, file_id, symbol_name, chunk_type, start_line, end_line, content)
                        VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, ?, ?)
                        """,
                C2, R1, F1, "Same#second", "SERVICE_METHOD", 30, 40, "CONTENT_TWO");
    }

    @Test
    void exactPredicateReturnsFullContentAndDoesNotCrossRepositoryScope() {
        CodeChunkExactReadResult first = mapper.selectByRepoIdAndChunkId(R1, C1);
        CodeChunkExactReadResult wrongRepo = mapper.selectByRepoIdAndChunkId(R2, C1);

        assertThat(first.getRepoId()).isEqualTo(R1);
        assertThat(first.getChunkId()).isEqualTo(C1);
        assertThat(first.getFilePath()).isEqualTo("src/main/java/example/Same.java");
        assertThat(first.getContent()).hasSizeGreaterThan(12_000).endsWith("_EXACT_TAIL");
        assertThat(wrongRepo).isNull();
    }

    @Test
    void sameFileDifferentChunkIdsReturnDifferentCanonicalBodies() {
        CodeChunkExactReadResult first = mapper.selectByRepoIdAndChunkId(R1, C1);
        CodeChunkExactReadResult second = mapper.selectByRepoIdAndChunkId(R1, C2);

        assertThat(first.getChunkId()).isEqualTo(C1);
        assertThat(first.getContent()).startsWith("CONTENT_ONE_");
        assertThat(second.getChunkId()).isEqualTo(C2);
        assertThat(second.getContent()).isEqualTo("CONTENT_TWO");
    }

    @Configuration
    static class Config {
        @Bean
        DataSource dataSource() {
            PGSimpleDataSource dataSource = new PGSimpleDataSource();
            dataSource.setURL(POSTGRES.getJdbcUrl());
            dataSource.setUser(POSTGRES.getUsername());
            dataSource.setPassword(POSTGRES.getPassword());
            return dataSource;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setTypeHandlers(new PgVectorTypeHandler());
            factory.setMapperLocations(new ClassPathResource("mapper/CodeChunkMapper.xml"));
            return factory.getObject();
        }

        @Bean
        CodeChunkMapper codeChunkMapper(SqlSessionFactory factory) throws Exception {
            MapperFactoryBean<CodeChunkMapper> bean = new MapperFactoryBean<>(CodeChunkMapper.class);
            bean.setSqlSessionFactory(factory);
            bean.afterPropertiesSet();
            return bean.getObject();
        }
    }
}
