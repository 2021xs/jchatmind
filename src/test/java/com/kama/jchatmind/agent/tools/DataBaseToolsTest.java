package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.config.DatabaseToolProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataBaseToolsTest {

    @Test
    void zeroOneFortyNineAndExactlyFiftyRowsAreComplete() throws Exception {
        for (int rowCount : List.of(0, 1, 49, 50)) {
            QueryExecution execution = execute("SELECT value FROM test_rows", values(rowCount), properties());

            assertThat(execution.result())
                    .contains("status: SUCCESS")
                    .contains("completeness: COMPLETE")
                    .contains("rowsReturned: " + rowCount)
                    .contains("rowLimit: 50")
                    .contains("hasMore: false");
            assertThat(execution.executableSql()).isEqualTo("SELECT value FROM test_rows LIMIT 51");
            verify(execution.jdbcTemplate()).setMaxRows(51);
        }
    }

    @Test
    void fiftyFirstRowIsProbeOnlyAndMarksResultPartial() throws Exception {
        QueryExecution execution = execute("SELECT value FROM test_rows", values(51), properties());

        assertThat(execution.result())
                .contains("status: PARTIAL")
                .contains("completeness: PARTIAL")
                .contains("rowsReturned: 50")
                .contains("rowLimit: 50")
                .contains("hasMore: true")
                .contains("Do not treat them as the complete query result.")
                .contains("ROW_050")
                .doesNotContain("ROW_051");
    }

    @Test
    void moreThanProbeLimitStillReturnsOnlyFiftyRows() throws Exception {
        QueryExecution execution = execute("SELECT value FROM test_rows", values(125), properties());

        assertThat(execution.result())
                .contains("completeness: PARTIAL")
                .contains("rowsReturned: 50")
                .contains("hasMore: true")
                .contains("ROW_050")
                .doesNotContain("ROW_051")
                .doesNotContain("ROW_125");
    }

    @Test
    void explicitLimitTenIsCompleteForThatExecutableQuery() throws Exception {
        QueryExecution execution = execute("SELECT value FROM test_rows LIMIT 10", values(10), properties());

        assertThat(execution.executableSql()).isEqualTo("SELECT value FROM test_rows LIMIT 10");
        assertThat(execution.result())
                .contains("completeness: COMPLETE")
                .contains("rowsReturned: 10")
                .contains("hasMore: false");
    }

    @Test
    void explicitLimitExactlyAtRowBoundaryIsCompleteForThatExecutableQuery() throws Exception {
        QueryExecution execution = execute("SELECT value FROM test_rows LIMIT 50", values(50), properties());

        assertThat(execution.executableSql()).isEqualTo("SELECT value FROM test_rows LIMIT 50");
        assertThat(execution.result())
                .contains("completeness: COMPLETE")
                .contains("rowsReturned: 50")
                .contains("hasMore: false");
    }

    @Test
    void explicitLimitAboveRowBoundaryIsCappedAtProbeLimit() throws Exception {
        QueryExecution execution = execute("SELECT value FROM test_rows LIMIT 100", values(51), properties());

        assertThat(execution.executableSql()).isEqualTo("SELECT value FROM test_rows LIMIT 51");
        assertThat(execution.result())
                .contains("completeness: PARTIAL")
                .contains("rowsReturned: 50")
                .contains("hasMore: true");
    }

    @Test
    void countValueDoesNotAffectRowCompleteness() throws Exception {
        QueryExecution execution = execute("SELECT COUNT(*) AS total FROM test_rows", List.of("125"), properties());

        assertThat(execution.executableSql()).isEqualTo("SELECT COUNT(*) AS total FROM test_rows LIMIT 51");
        assertThat(execution.result())
                .contains("completeness: COMPLETE")
                .contains("rowsReturned: 1")
                .contains("hasMore: false")
                .contains("125");
    }

    @Test
    void groupByRemainsARegularBoundedSelect() throws Exception {
        QueryExecution execution = execute(
                "SELECT category, COUNT(*) FROM test_rows GROUP BY category",
                List.of("A | 3", "B | 4"), properties());

        assertThat(execution.executableSql())
                .isEqualTo("SELECT category, COUNT(*) FROM test_rows GROUP BY category LIMIT 51");
        assertThat(execution.result())
                .contains("completeness: COMPLETE")
                .contains("rowsReturned: 2")
                .contains("hasMore: false");
    }

    @Test
    void cellBoundaryKeepsAnExplicitTruncationMarker() throws Exception {
        DatabaseToolProperties properties = properties();
        properties.setMaxCellChars(40);
        QueryExecution execution = execute(
                "SELECT value FROM test_rows",
                List.of("CELL_HEAD_" + "x".repeat(100) + "_CELL_TAIL"), properties);

        assertThat(execution.result())
                .contains("[TRUNCATED_CELL]")
                .doesNotContain("_CELL_TAIL")
                .contains("completeness: COMPLETE")
                .contains("hasMore: false");
    }

    @Test
    void formattedCanonicalResultIsNotTruncatedAtLegacyFourThousandChars() throws Exception {
        List<String> rows = IntStream.rangeClosed(1, 50)
                .mapToObj(index -> "ROW_%03d_%s".formatted(index, "x".repeat(100)))
                .toList();
        QueryExecution execution = execute("SELECT value FROM test_rows", rows, properties());

        assertThat(execution.result())
                .hasSizeGreaterThan(4_000)
                .contains("ROW_050_")
                .doesNotContain("...[truncated]")
                .contains("completeness: COMPLETE")
                .contains("hasMore: false");
    }

    private QueryExecution execute(String sql, List<String> values,
                                   DatabaseToolProperties properties) throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ResultSet resultSet = resultSet(values);
        AtomicReference<String> executableSql = new AtomicReference<>();
        when(jdbcTemplate.query(any(String.class),
                org.mockito.ArgumentMatchers.<ResultSetExtractor<List<String>>>any()))
                .thenAnswer(invocation -> {
                    executableSql.set(invocation.getArgument(0));
                    ResultSetExtractor<List<String>> extractor = invocation.getArgument(1);
                    return extractor.extractData(resultSet);
                });

        DataBaseTools tool = new DataBaseTools(jdbcTemplate, new SqlSafetyValidator(), properties);
        String result = tool.query(sql);
        return new QueryExecution(result, executableSql.get(), jdbcTemplate);
    }

    private ResultSet resultSet(List<String> values) throws Exception {
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnName(1)).thenReturn("value");

        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getMetaData()).thenReturn(metadata);
        AtomicInteger cursor = new AtomicInteger(-1);
        when(resultSet.next()).thenAnswer(ignored -> cursor.incrementAndGet() < values.size());
        when(resultSet.getObject(1)).thenAnswer(ignored -> values.get(cursor.get()));
        return resultSet;
    }

    private List<String> values(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(index -> "ROW_%03d".formatted(index))
                .toList();
    }

    private DatabaseToolProperties properties() {
        return new DatabaseToolProperties();
    }

    private record QueryExecution(String result, String executableSql, JdbcTemplate jdbcTemplate) {
    }
}
