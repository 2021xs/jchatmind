package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.mapper.CodeRepositoryMapper;
import com.kama.jchatmind.model.dto.CodeRagExecutionResult;
import com.kama.jchatmind.model.entity.CodeRepository;
import com.kama.jchatmind.service.CodeRagAnswerEvidenceService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real, sequential diagnostic for DeepSeek selector reasoning. Disabled unless explicitly enabled.
 */
@Tag("rag-selector-reasoning")
@EnabledIf("diagnosticEnabled")
@SpringBootTest
class CodeRagSelectorReasoningDiagnosticTest {
    private static final String REPOSITORY_ID = "bf4ef891-330b-4ce8-9002-ba4c43ffe210";
    private static final String REPOSITORY_NAME = "FlashDeal";
    private static final String SELECTOR_MODEL = "deepseek-chat";
    private static final int RAW_TOP_K = 20;
    private static final long TIMEOUT_MS = 30_000;
    private static final int RUNS_PER_QUERY = 5;
    private static final Path OUTPUT_DIR = Path.of("target", "eval");
    private static final List<QueryCase> QUERIES = List.of(
            new QueryCase("Q1", "项目介绍 README 秒杀系统 FlashDeal 概述"),
            new QueryCase("Q2", "Controller 接口入口 API 路径 VoucherOrderController 秒杀"),
            new QueryCase("Q3", "README 架构设计 秒杀流程 技术要点 可靠性 幂等 库存预热"),
            new QueryCase("Q4", "项目模块结构 controller service mapper entity")
    );

    @Autowired
    private CodeRagAnswerEvidenceService answerEvidenceService;

    @Autowired
    private CodeRepositoryMapper codeRepositoryMapper;

    @Autowired
    private CodeRagProperties properties;

    @Autowired
    private ChatClientRegistry chatClientRegistry;

    @Value("${jchatmind.ai.deepseek.official.model:UNAVAILABLE}")
    private String providerModel;

    @Test
    void measureVisibleAndHiddenSelectorOutput() throws Exception {
        CodeRepository repository = requireFixedEnvironment();
        List<Record> records = new ArrayList<>(QUERIES.size() * RUNS_PER_QUERY);

        for (QueryCase queryCase : QUERIES) {
            for (int run = 1; run <= RUNS_PER_QUERY; run++) {
                CodeRagExecutionResult execution = answerEvidenceService.execute(repository.getId(), queryCase.query());
                assertNotNull(execution.getAnswerEvidence(), "Code RAG returned no answer evidence");
                assertEquals(RAW_TOP_K, execution.getRawCandidates().size(),
                        "Fixed repository must supply exactly rawTopK candidates");
                records.add(Record.from(queryCase, run, execution));
            }
        }

        assertEquals(QUERIES.size() * RUNS_PER_QUERY, records.size());
        writeCsv(records);
        writeReport(repository, records);
    }

    private CodeRepository requireFixedEnvironment() {
        assertEquals(RAW_TOP_K, properties.getAnswerEvidence().getRawTopK());
        assertEquals(5, properties.getAnswerEvidence().getFinalTopK());
        assertEquals(TIMEOUT_MS, properties.getLlmSelector().getTimeoutMs());
        assertEquals(SELECTOR_MODEL, properties.getLlmSelector().getModel());
        assertTrue(chatClientRegistry.contains(SELECTOR_MODEL), "deepseek-chat ChatClient is not registered");

        CodeRepository repository = codeRepositoryMapper.selectById(REPOSITORY_ID);
        assertNotNull(repository, "Fixed FlashDeal repository does not exist");
        assertEquals(REPOSITORY_NAME, repository.getName());
        assertEquals("READY", repository.getStatus());
        return repository;
    }

    private void writeCsv(List<Record> records) throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        StringBuilder csv = new StringBuilder(
                "timestamp,query_id,query,run_index,prompt_chars,candidate_section_chars,visible_content,visible_content_chars,reasoning_content_chars,reasoning_content_present,reasoning_tokens,prompt_tokens,completion_tokens,total_tokens,selector_latency_ms,finish_reason,json_parse_ok,fallback,fallback_reason\n");
        records.forEach(record -> csv.append(record.csv()).append('\n'));
        Files.writeString(OUTPUT_DIR.resolve("code-rag-selector-reasoning.csv"), csv.toString(),
                StandardCharsets.UTF_8);
    }

    private void writeReport(CodeRepository repository, List<Record> records) throws IOException {
        StringBuilder report = new StringBuilder("# Code RAG Selector Hidden Reasoning Diagnostic\n\n")
                .append("- timestamp: ").append(OffsetDateTime.now()).append('\n')
                .append("- repository: ").append(repository.getName()).append(" (")
                .append(repository.getId()).append(")\n")
                .append("- selector model: ").append(SELECTOR_MODEL).append('\n')
                .append("- provider request model: ").append(providerModel).append('\n')
                .append("- rawTopK: ").append(RAW_TOP_K).append('\n')
                .append("- finalTopK: 5\n")
                .append("- timeout-ms: ").append(TIMEOUT_MS).append('\n')
                .append("- execution: sequential, concurrency=1\n")
                .append("- runs per query: ").append(RUNS_PER_QUERY).append('\n')
                .append("- reasoningTokens: UNAVAILABLE in Spring AI 1.1.0 DeepSeek usage\n\n")
                .append("## Raw Results\n\n")
                .append("| query | run | visible chars | reasoning chars | prompt tokens | completion tokens | latency ms | finish reason | fallback | fallback reason |\n")
                .append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- | --- |\n");
        for (Record record : records) {
            report.append("| ").append(record.queryId()).append(" | ").append(record.runIndex())
                    .append(" | ").append(record.visibleContentChars())
                    .append(" | ").append(nullable(record.reasoningContentChars()))
                    .append(" | ").append(nullable(record.promptTokens()))
                    .append(" | ").append(nullable(record.completionTokens()))
                    .append(" | ").append(record.selectorLatencyMs())
                    .append(" | ").append(table(record.finishReason()))
                    .append(" | ").append(record.fallback())
                    .append(" | ").append(table(record.fallbackReason())).append(" |\n");
        }

        report.append("\n## Per-query Summary\n\n")
                .append("| query | success | timeout | avg reasoning chars | avg completion tokens | P50 latency ms | P95 latency ms |\n")
                .append("| --- | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (QueryCase queryCase : QUERIES) {
            List<Record> group = records.stream().filter(record -> record.queryId().equals(queryCase.id())).toList();
            List<Long> latencies = group.stream().map(Record::selectorLatencyMs).sorted().toList();
            report.append("| ").append(queryCase.id())
                    .append(" | ").append(group.stream().filter(record -> !record.fallback()).count())
                    .append(" | ").append(group.stream().filter(Record::timeout).count())
                    .append(" | ").append(average(group.stream().map(Record::reasoningContentChars).toList()))
                    .append(" | ").append(average(group.stream().map(Record::completionTokens).toList()))
                    .append(" | ").append(percentile(latencies, 0.50))
                    .append(" | ").append(percentile(latencies, 0.95)).append(" |\n");
        }

        List<Record> successful = records.stream().filter(record -> !record.fallback()).toList();
        report.append("\n## Correlations (successful requests)\n\n")
                .append("- completionTokens vs selectorLatencyMs: ")
                .append(correlation(successful, Metric.COMPLETION_TOKENS, Metric.LATENCY)).append('\n')
                .append("- reasoningContentChars vs completionTokens: ")
                .append(correlation(successful, Metric.REASONING_CHARS, Metric.COMPLETION_TOKENS)).append('\n')
                .append("- reasoningContentChars vs selectorLatencyMs: ")
                .append(correlation(successful, Metric.REASONING_CHARS, Metric.LATENCY)).append('\n')
                .append("- visibleContentChars vs selectorLatencyMs: ")
                .append(correlation(successful, Metric.VISIBLE_CHARS, Metric.LATENCY)).append('\n');

        Files.writeString(OUTPUT_DIR.resolve("code-rag-selector-reasoning.md"), report.toString(),
                StandardCharsets.UTF_8);
    }

    private String correlation(List<Record> records, Metric xMetric, Metric yMetric) {
        List<double[]> pairs = records.stream()
                .map(record -> new double[]{xMetric.value(record), yMetric.value(record)})
                .filter(pair -> !Double.isNaN(pair[0]) && !Double.isNaN(pair[1]))
                .toList();
        if (pairs.size() < 2) {
            return "UNAVAILABLE";
        }
        double xMean = pairs.stream().mapToDouble(pair -> pair[0]).average().orElse(Double.NaN);
        double yMean = pairs.stream().mapToDouble(pair -> pair[1]).average().orElse(Double.NaN);
        double numerator = 0;
        double xSquares = 0;
        double ySquares = 0;
        for (double[] pair : pairs) {
            double x = pair[0] - xMean;
            double y = pair[1] - yMean;
            numerator += x * y;
            xSquares += x * x;
            ySquares += y * y;
        }
        double denominator = Math.sqrt(xSquares * ySquares);
        return denominator == 0 ? "UNAVAILABLE" : format(numerator / denominator);
    }

    private static String average(List<Integer> values) {
        List<Integer> available = values.stream().filter(value -> value != null).toList();
        return available.isEmpty() ? "UNAVAILABLE"
                : format(available.stream().mapToInt(Integer::intValue).average().orElse(0));
    }

    private static long percentile(List<Long> sortedValues, double quantile) {
        if (sortedValues.isEmpty()) {
            return 0;
        }
        int index = Math.min(sortedValues.size() - 1,
                Math.max(0, (int) Math.ceil(sortedValues.size() * quantile) - 1));
        return sortedValues.get(index);
    }

    private static String nullable(Object value) {
        return value == null ? "UNAVAILABLE" : value.toString();
    }

    private static String csv(Object value) {
        if (value == null) {
            return "UNAVAILABLE";
        }
        return "\"" + value.toString().replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\"";
    }

    private static String table(String value) {
        return value == null ? "UNAVAILABLE" : value.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    static boolean diagnosticEnabled() {
        return Boolean.getBoolean("code.rag.selector.reasoning.enabled");
    }

    private enum Metric {
        COMPLETION_TOKENS {
            @Override
            double value(Record record) {
                return record.completionTokens() == null ? Double.NaN : record.completionTokens();
            }
        },
        REASONING_CHARS {
            @Override
            double value(Record record) {
                return record.reasoningContentChars() == null ? Double.NaN : record.reasoningContentChars();
            }
        },
        VISIBLE_CHARS {
            @Override
            double value(Record record) {
                return record.visibleContentChars();
            }
        },
        LATENCY {
            @Override
            double value(Record record) {
                return record.selectorLatencyMs();
            }
        };

        abstract double value(Record record);
    }

    private record QueryCase(String id, String query) {
    }

    private record Record(String timestamp, String queryId, String query, int runIndex,
                          int promptChars, int candidateSectionChars, String visibleContent,
                          int visibleContentChars, Integer reasoningContentChars,
                          Boolean reasoningContentPresent, Integer promptTokens,
                          Integer completionTokens, Integer totalTokens, long selectorLatencyMs,
                          String finishReason, boolean jsonParseOk, boolean fallback, String fallbackReason) {
        static Record from(QueryCase queryCase, int runIndex, CodeRagExecutionResult execution) {
            String visibleContent = execution.getSelectorVisibleContent();
            return new Record(OffsetDateTime.now().toString(), queryCase.id(), queryCase.query(), runIndex,
                    execution.getSelectorPromptChars(), execution.getSelectorCandidateSectionChars(), visibleContent,
                    visibleContent == null ? 0 : visibleContent.length(),
                    execution.getSelectorReasoningContentChars(), execution.getSelectorReasoningContentPresent(),
                    execution.getSelectorPromptTokens(), execution.getSelectorCompletionTokens(),
                    execution.getSelectorTotalTokens(), execution.getSelectorLatencyMs(),
                    execution.getSelectorFinishReason(), execution.getAnswerEvidence().isJsonParseOk(),
                    execution.getAnswerEvidence().isFallback(), execution.getSelectorFallbackReason());
        }

        boolean timeout() {
            return fallbackReason != null && fallbackReason.startsWith("SELECTOR_TIMEOUT:");
        }

        String csv() {
            return String.join(",", timestamp, queryId, CodeRagSelectorReasoningDiagnosticTest.csv(query),
                    Integer.toString(runIndex), Integer.toString(promptChars),
                    Integer.toString(candidateSectionChars),
                    visibleContent == null ? "UNAVAILABLE" : CodeRagSelectorReasoningDiagnosticTest.csv(visibleContent),
                    Integer.toString(visibleContentChars), nullable(reasoningContentChars),
                    nullable(reasoningContentPresent), "UNAVAILABLE", nullable(promptTokens),
                    nullable(completionTokens), nullable(totalTokens), Long.toString(selectorLatencyMs),
                    CodeRagSelectorReasoningDiagnosticTest.csv(finishReason), Boolean.toString(jsonParseOk),
                    Boolean.toString(fallback), CodeRagSelectorReasoningDiagnosticTest.csv(fallbackReason));
        }
    }
}
