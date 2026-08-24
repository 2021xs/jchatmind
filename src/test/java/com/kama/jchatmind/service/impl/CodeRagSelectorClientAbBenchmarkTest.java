package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.mapper.CodeRepositoryMapper;
import com.kama.jchatmind.model.dto.CodeEvidenceCandidateCard;
import com.kama.jchatmind.model.dto.CodeEvidenceSelectionResult;
import com.kama.jchatmind.model.dto.CodeSearchExecutionResult;
import com.kama.jchatmind.model.dto.CodeSearchResult;
import com.kama.jchatmind.model.entity.CodeRepository;
import com.kama.jchatmind.service.CodeSearchService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Real, sequential A/B benchmark for the current Spring AI selector path and the direct DeepSeek HTTP path.
 * Disabled unless explicitly enabled because it performs retrieval and calls the configured provider.
 */
@Tag("rag-selector-client-ab")
@EnabledIf("benchmarkEnabled")
@SpringBootTest
class CodeRagSelectorClientAbBenchmarkTest {
    private static final String REPOSITORY_ID = "bf4ef891-330b-4ce8-9002-ba4c43ffe210";
    private static final String REPOSITORY_NAME = "FlashDeal";
    private static final String SELECTOR_REGISTRY_KEY = "deepseek-chat";
    private static final String PROVIDER_MODEL = "deepseek-v4-flash";
    private static final int RAW_TOP_K = 20;
    private static final int FINAL_TOP_K = 5;
    private static final long TIMEOUT_MS = 30_000;
    private static final int RUNS_PER_QUERY = 5;
    private static final int EXPECTED_CALLS = 40;
    private static final Path OUTPUT_DIR = Path.of("target", "eval");
    private static final Path CSV_PATH = OUTPUT_DIR.resolve("code-rag-selector-client-ab.csv");
    private static final Path REPORT_PATH = OUTPUT_DIR.resolve("code-rag-selector-client-ab.md");
    private static final List<QueryCase> QUERIES = List.of(
            new QueryCase("Q1", "项目介绍 README 秒杀系统 FlashDeal 概述"),
            new QueryCase("Q2", "Controller 接口入口 API 路径 VoucherOrderController 秒杀"),
            new QueryCase("Q3", "README 架构设计 秒杀流程 技术要点 可靠性 幂等 库存预热"),
            new QueryCase("Q4", "项目模块结构 controller service mapper entity")
    );

    @Autowired
    private CodeSearchService codeSearchService;

    @Autowired
    private CodeRepositoryMapper codeRepositoryMapper;

    @Autowired
    private CodeRagProperties properties;

    @Autowired
    private ChatClientRegistry chatClientRegistry;

    @Autowired
    private SpringAiLlmSelectorClient springAiClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RestClient.Builder restClientBuilder;

    @Autowired
    @Qualifier("codeEvidenceSelectorExecutor")
    private AsyncTaskExecutor selectorExecutor;

    @Value("${jchatmind.ai.deepseek.official.base-url:}")
    private String providerBaseUrl;

    @Value("${jchatmind.ai.deepseek.official.api-key:}")
    private String providerApiKey;

    @Value("${jchatmind.ai.deepseek.official.model:}")
    private String providerModel;

    @Test
    void compareSpringAiBaselineWithThinkingDisabledHttpClient() throws Exception {
        CodeRepository repository = requireFixedEnvironment();

        // Freeze and validate every query before the first provider call.
        Map<String, FrozenCandidates> frozenByQuery = freezeAllCandidates();
        CodeLlmEvidenceSelector baselineSelector = new CodeLlmEvidenceSelector(
                springAiClient, properties, objectMapper, selectorExecutor);
        DeepSeekLlmSelectorClient directClient = new DeepSeekLlmSelectorClient(
                restClientBuilder, objectMapper, providerBaseUrl, providerApiKey, providerModel);
        CodeLlmEvidenceSelector experimentSelector = new CodeLlmEvidenceSelector(
                directClient, properties, objectMapper, selectorExecutor);

        List<Observation> observations = new ArrayList<>(EXPECTED_CALLS);
        int sequenceIndex = 0;
        for (int queryIndex = 0; queryIndex < QUERIES.size(); queryIndex++) {
            QueryCase queryCase = QUERIES.get(queryIndex);
            FrozenCandidates frozen = frozenByQuery.get(queryCase.id());
            ClientType first = queryIndex % 2 == 0 ? ClientType.SPRING_AI : ClientType.DEEPSEEK_HTTP;
            ClientType second = first == ClientType.SPRING_AI
                    ? ClientType.DEEPSEEK_HTTP : ClientType.SPRING_AI;
            for (int run = 1; run <= RUNS_PER_QUERY; run++) {
                sequenceIndex = executeAndRecord(observations, ++sequenceIndex, queryCase, run, first,
                        selector(first, baselineSelector, experimentSelector), frozen);
                sequenceIndex = executeAndRecord(observations, ++sequenceIndex, queryCase, run, second,
                        selector(second, baselineSelector, experimentSelector), frozen);
            }
        }

        require(observations.size() == EXPECTED_CALLS,
                "Benchmark must produce exactly " + EXPECTED_CALLS + " observations");
        writeCsv(observations);
        writeReport(repository, frozenByQuery, observations);
    }

    private CodeRepository requireFixedEnvironment() {
        require(properties.getLlmSelector().isEnabled(), "LLM selector must be enabled");
        require(properties.getAnswerEvidence().getRawTopK() == RAW_TOP_K, "rawTopK must be 20");
        require(properties.getAnswerEvidence().getFinalTopK() == FINAL_TOP_K, "finalTopK must be 5");
        require(properties.getLlmSelector().getMaxSelected() == FINAL_TOP_K, "selector maxSelected must be 5");
        require(properties.getLlmSelector().getTimeoutMs() == TIMEOUT_MS, "selector timeout must be 30000ms");
        require(SELECTOR_REGISTRY_KEY.equals(properties.getLlmSelector().getModel()),
                "Spring AI baseline selector key must be deepseek-chat");
        require(chatClientRegistry.contains(SELECTOR_REGISTRY_KEY),
                "Spring AI deepseek-chat client is not registered");
        require(PROVIDER_MODEL.equals(providerModel), "provider model must be deepseek-v4-flash");
        require(StringUtils.hasText(providerBaseUrl), "DeepSeek provider base URL is missing");
        require(StringUtils.hasText(providerApiKey) && !"your-api-key".equals(providerApiKey),
                "DeepSeek provider API key is missing or still uses the placeholder");

        CodeRepository repository = codeRepositoryMapper.selectById(REPOSITORY_ID);
        require(repository != null, "Fixed FlashDeal repository does not exist");
        require(REPOSITORY_ID.equals(repository.getId()), "repository id does not match the fixed repoId");
        require(REPOSITORY_NAME.equals(repository.getName()), "repository name must be FlashDeal");
        require("READY".equalsIgnoreCase(repository.getStatus()), "repository status must be READY");
        return repository;
    }

    private Map<String, FrozenCandidates> freezeAllCandidates() {
        Map<String, FrozenCandidates> frozenByQuery = new LinkedHashMap<>();
        for (QueryCase queryCase : QUERIES) {
            CodeSearchExecutionResult search = codeSearchService.searchWithTrace(
                    REPOSITORY_ID, queryCase.query(), RAW_TOP_K);
            require(search != null && search.getCandidates() != null,
                    queryCase.id() + " retrieval returned no candidate list");
            require(search.getCandidates().size() == RAW_TOP_K,
                    queryCase.id() + " candidate count must be exactly " + RAW_TOP_K);
            List<CodeEvidenceCandidateCard> cards = List.copyOf(toCandidateCards(search.getCandidates()));
            String fingerprint = fingerprint(cards);
            require(StringUtils.hasText(fingerprint), queryCase.id() + " candidate fingerprint is missing");
            frozenByQuery.put(queryCase.id(), new FrozenCandidates(cards, fingerprint));
        }
        require(frozenByQuery.size() == QUERIES.size(), "Not all query candidates were frozen");
        return Map.copyOf(frozenByQuery);
    }

    private List<CodeEvidenceCandidateCard> toCandidateCards(List<CodeSearchResult> raw) {
        int maxSnippetChars = properties.getLlmSelector().getMaxCandidateChars();
        List<CodeEvidenceCandidateCard> cards = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            CodeSearchResult result = raw.get(i);
            String chunkId = StringUtils.hasText(result.getChunkId()) ? result.getChunkId() : "candidate-" + i;
            cards.add(CodeEvidenceCandidateCard.builder()
                    .chunkId(chunkId)
                    .chunkType(result.getChunkType())
                    .filePath(result.getFilePath())
                    .symbolName(result.getSymbolName())
                    .apiPath(result.getApiPath())
                    .httpMethod(result.getHttpMethod())
                    .startLine(result.getStartLine())
                    .endLine(result.getEndLine())
                    .metadataSummary(result.getMetadata())
                    .snippet(truncate(result.getContentPreview(), maxSnippetChars))
                    .source("RAW_VECTOR")
                    .rawRank(i + 1)
                    .candidateRank(i + 1)
                    .candidateScore(result.getFinalScore() == null ? result.getScore() : result.getFinalScore())
                    .build());
        }
        return cards;
    }

    private int executeAndRecord(List<Observation> observations,
                                 int sequenceIndex,
                                 QueryCase queryCase,
                                 int run,
                                 ClientType clientType,
                                 CodeLlmEvidenceSelector selector,
                                 FrozenCandidates frozen) {
        require(frozen.fingerprint().equals(fingerprint(frozen.cards())),
                queryCase.id() + " candidate data changed before sequence " + sequenceIndex);
        CodeEvidenceSelectionResult result = selector.select(queryCase.query(), frozen.cards());
        require(frozen.fingerprint().equals(fingerprint(frozen.cards())),
                queryCase.id() + " candidate data changed during sequence " + sequenceIndex);

        Observation observation = Observation.from(
                sequenceIndex, queryCase, run, clientType, frozen, result);
        if (isFatalInfrastructureFailure(observation.fallbackReason())) {
            throw new IllegalStateException("Provider infrastructure failure at sequence " + sequenceIndex
                    + ": " + observation.fallbackReason());
        }
        observations.add(observation);
        return sequenceIndex;
    }

    private CodeLlmEvidenceSelector selector(ClientType type,
                                               CodeLlmEvidenceSelector baseline,
                                               CodeLlmEvidenceSelector experiment) {
        return type == ClientType.SPRING_AI ? baseline : experiment;
    }

    private boolean isFatalInfrastructureFailure(String reason) {
        if (!StringUtils.hasText(reason) || !reason.startsWith("MODEL_ERROR:")) {
            return false;
        }
        String normalized = reason.toLowerCase(Locale.ROOT);
        return normalized.contains("401")
                || normalized.contains("403")
                || normalized.contains("unauthorized")
                || normalized.contains("forbidden")
                || normalized.contains("unknown host")
                || normalized.contains("unknownhost")
                || normalized.contains("connection refused")
                || normalized.contains("no route to host");
    }

    private String fingerprint(List<CodeEvidenceCandidateCard> cards) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < cards.size(); i++) {
                CodeEvidenceCandidateCard card = cards.get(i);
                updateDigest(digest, localId(i));
                updateDigest(digest, card.getChunkId());
                updateDigest(digest, card.getFilePath());
                updateDigest(digest, card.getSymbolName());
                updateDigest(digest, card.getChunkType());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private void updateDigest(MessageDigest digest, String value) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
        digest.update((byte) '\n');
    }

    private void writeCsv(List<Observation> observations) throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        StringBuilder csv = new StringBuilder(
                "timestamp,sequence_index,query_id,query,run,client_type,candidate_count,candidate_fingerprint,latency_ms,reasoning_content_present,reasoning_content_chars,prompt_tokens,completion_tokens,total_tokens,usage_available,finish_reason,timeout,fallback,fallback_reason,json_parse_ok,proposed_candidate_ids,effective_selected_candidate_ids,selected_chunk_ids,selected_evidence\n");
        for (Observation observation : observations) {
            csv.append(observation.timestamp()).append(',')
                    .append(observation.sequenceIndex()).append(',')
                    .append(observation.queryId()).append(',')
                    .append(csv(observation.query())).append(',')
                    .append(observation.run()).append(',')
                    .append(observation.clientType()).append(',')
                    .append(observation.candidateCount()).append(',')
                    .append(observation.candidateFingerprint()).append(',')
                    .append(observation.latencyMs()).append(',')
                    .append(nullable(observation.reasoningContentPresent())).append(',')
                    .append(nullable(observation.reasoningContentChars())).append(',')
                    .append(nullable(observation.promptTokens())).append(',')
                    .append(nullable(observation.completionTokens())).append(',')
                    .append(nullable(observation.totalTokens())).append(',')
                    .append(observation.usageAvailable()).append(',')
                    .append(csv(observation.finishReason())).append(',')
                    .append(observation.timeout()).append(',')
                    .append(observation.fallback()).append(',')
                    .append(csv(observation.fallbackReason())).append(',')
                    .append(observation.jsonParseOk()).append(',')
                    .append(csv(json(observation.proposedCandidateIds()))).append(',')
                    .append(csv(json(observation.effectiveSelectedCandidateIds()))).append(',')
                    .append(csv(json(observation.selectedChunkIds()))).append(',')
                    .append(csv(json(observation.selectedEvidence())))
                    .append('\n');
        }
        Files.writeString(CSV_PATH, csv.toString(), StandardCharsets.UTF_8);
    }

    private void writeReport(CodeRepository repository,
                             Map<String, FrozenCandidates> frozenByQuery,
                             List<Observation> observations) throws IOException {
        StringBuilder report = new StringBuilder("# Code RAG Selector Client A/B Benchmark\n\n")
                .append("- timestamp: ").append(OffsetDateTime.now()).append('\n')
                .append("- repository: ").append(repository.getName()).append(" (")
                .append(repository.getId()).append(")\n")
                .append("- comparison: DEEPSEEK_HTTP + thinking.disabled path vs current SPRING_AI baseline\n")
                .append("- baseline: SPRING_AI + default thinking + current Spring AI retry semantics\n")
                .append("- experiment: DEEPSEEK_HTTP + thinking.type=disabled + retry=0\n")
                .append("- provider model: ").append(providerModel).append('\n')
                .append("- rawTopK: ").append(RAW_TOP_K).append('\n')
                .append("- finalTopK: ").append(FINAL_TOP_K).append('\n')
                .append("- timeout-ms: ").append(TIMEOUT_MS).append('\n')
                .append("- runs per query/client: ").append(RUNS_PER_QUERY).append('\n')
                .append("- calls: ").append(observations.size()).append('\n')
                .append("- execution: sequential, concurrency=1, alternating A/B order\n")
                .append("- attribution note: performance differences cannot be attributed to thinking alone because transport and retry semantics also differ.\n\n")
                .append("## Candidate Freeze\n\n")
                .append("| Query | Count | SHA-256 fingerprint |\n")
                .append("| --- | ---: | --- |\n");
        for (QueryCase queryCase : QUERIES) {
            FrozenCandidates frozen = frozenByQuery.get(queryCase.id());
            report.append("| ").append(queryCase.id()).append(" | ")
                    .append(frozen.cards().size()).append(" | `")
                    .append(frozen.fingerprint()).append("` |\n");
        }

        report.append("\n## Overall Results\n\n")
                .append("| Client | Calls | Success | Timeout | Timeout rate | Fallback | Fallback rate | Mean ms | P50 ms | P95 ms | Max ms | Usage available | Mean completion tokens | P50 completion tokens | Max completion tokens | Mean reasoning chars | P50 reasoning chars | Max reasoning chars |\n")
                .append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (ClientType type : ClientType.values()) {
            Summary summary = Summary.from(observations.stream()
                    .filter(observation -> observation.clientType() == type).toList());
            report.append(summary.row(type)).append('\n');
        }

        report.append("\n## Per-query Calls\n\n")
                .append("| Seq | Query | Run | Client | Latency ms | Reasoning chars | Completion tokens | Timeout | Fallback | Selected IDs | Evidence |\n")
                .append("| ---: | --- | ---: | --- | ---: | ---: | ---: | --- | --- | --- | --- |\n");
        for (Observation observation : observations) {
            report.append("| ").append(observation.sequenceIndex())
                    .append(" | ").append(observation.queryId())
                    .append(" | ").append(observation.run())
                    .append(" | ").append(observation.clientType())
                    .append(" | ").append(observation.latencyMs())
                    .append(" | ").append(nullable(observation.reasoningContentChars()))
                    .append(" | ").append(nullable(observation.completionTokens()))
                    .append(" | ").append(observation.timeout())
                    .append(" | ").append(observation.fallback())
                    .append(" | ").append(table(observation.effectiveSelectedCandidateIds().toString()))
                    .append(" | ").append(table(evidenceText(observation.selectedEvidence())))
                    .append(" |\n");
        }

        report.append("\n## Selection Comparison\n\n")
                .append("Exact match is ordered. Jaccard compares the effective selected ID sets.\n\n")
                .append("| Query | Run | Exact match | Jaccard | Baseline IDs | Experiment IDs |\n")
                .append("| --- | ---: | --- | ---: | --- | --- |\n");
        List<Comparison> comparisons = comparisons(observations);
        for (Comparison comparison : comparisons) {
            report.append("| ").append(comparison.queryId())
                    .append(" | ").append(comparison.run())
                    .append(" | ").append(comparison.exactMatch())
                    .append(" | ").append(format(comparison.jaccard()))
                    .append(" | ").append(table(comparison.baselineIds().toString()))
                    .append(" | ").append(table(comparison.experimentIds().toString()))
                    .append(" |\n");
        }

        report.append("\n## Per-query Selection Summary\n\n")
                .append("| Query | Exact-match rate | Mean Jaccard |\n")
                .append("| --- | ---: | ---: |\n");
        for (QueryCase queryCase : QUERIES) {
            List<Comparison> group = comparisons.stream()
                    .filter(comparison -> comparison.queryId().equals(queryCase.id())).toList();
            report.append("| ").append(queryCase.id())
                    .append(" | ").append(rate(group.stream().filter(Comparison::exactMatch).count(), group.size()))
                    .append(" | ").append(format(group.stream().mapToDouble(Comparison::jaccard).average().orElse(0)))
                    .append(" |\n");
        }

        report.append("\n## Manual Evidence Review\n\n")
                .append("No exact golden labels exist for these four queries. The following distinct outputs are for human review; no automatic quality claim is made.\n");
        for (QueryCase queryCase : QUERIES) {
            report.append("\n### ").append(queryCase.id()).append("\n\n")
                    .append(queryCase.query()).append("\n\n");
            for (ClientType type : ClientType.values()) {
                report.append("- ").append(type).append(":\n");
                observations.stream()
                        .filter(observation -> observation.queryId().equals(queryCase.id()))
                        .filter(observation -> observation.clientType() == type)
                        .map(observation -> evidenceText(observation.selectedEvidence()))
                        .distinct()
                        .forEach(evidence -> report.append("  - ").append(evidence).append('\n'));
            }
        }

        report.append("\n## Interpretation Boundary\n\n")
                .append("This benchmark compares the complete DEEPSEEK_HTTP + thinking.disabled path with the current SPRING_AI baseline. ")
                .append("Reasoning chars, completion tokens, and latency can show whether hidden reasoning remains the strongest observed performance difference, ")
                .append("but transport and retry differences prevent single-variable causal attribution.\n");

        Files.createDirectories(OUTPUT_DIR);
        Files.writeString(REPORT_PATH, report.toString(), StandardCharsets.UTF_8);
    }

    private List<Comparison> comparisons(List<Observation> observations) {
        List<Comparison> comparisons = new ArrayList<>(QUERIES.size() * RUNS_PER_QUERY);
        for (QueryCase queryCase : QUERIES) {
            for (int run = 1; run <= RUNS_PER_QUERY; run++) {
                int pairedRun = run;
                Observation baseline = find(observations, queryCase.id(), pairedRun, ClientType.SPRING_AI);
                Observation experiment = find(observations, queryCase.id(), pairedRun, ClientType.DEEPSEEK_HTTP);
                List<String> baselineIds = baseline.effectiveSelectedCandidateIds();
                List<String> experimentIds = experiment.effectiveSelectedCandidateIds();
                comparisons.add(new Comparison(queryCase.id(), pairedRun, baselineIds.equals(experimentIds),
                        jaccard(baselineIds, experimentIds), baselineIds, experimentIds));
            }
        }
        return comparisons;
    }

    private Observation find(List<Observation> observations, String queryId, int run, ClientType type) {
        return observations.stream()
                .filter(observation -> observation.queryId().equals(queryId))
                .filter(observation -> observation.run() == run)
                .filter(observation -> observation.clientType() == type)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Missing observation for " + queryId + " run " + run + " client " + type));
    }

    private double jaccard(List<String> left, List<String> right) {
        Set<String> union = new LinkedHashSet<>(left);
        union.addAll(right);
        if (union.isEmpty()) {
            return 1;
        }
        Set<String> intersection = new LinkedHashSet<>(left);
        intersection.retainAll(right);
        return (double) intersection.size() / union.size();
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars);
    }

    private static String localId(int zeroBasedIndex) {
        return String.format(Locale.ROOT, "C%02d", zeroBasedIndex + 1);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize benchmark observation", e);
        }
    }

    private static String evidenceText(List<EvidenceRef> evidence) {
        if (evidence.isEmpty()) {
            return "[]";
        }
        return evidence.stream()
                .map(item -> item.localId() + " " + display(item.file()) + " / "
                        + display(item.symbol()) + " / " + display(item.chunkType()))
                .reduce((left, right) -> left + "; " + right)
                .orElse("[]");
    }

    private static String display(String value) {
        return StringUtils.hasText(value) ? value : "UNAVAILABLE";
    }

    private static String nullable(Object value) {
        return value == null ? "UNAVAILABLE" : value.toString();
    }

    private static String csv(Object value) {
        if (value == null) {
            return "UNAVAILABLE";
        }
        return "\"" + value.toString().replace("\"", "\"\"")
                .replace("\r", " ").replace("\n", " ") + "\"";
    }

    private static String table(String value) {
        return value == null ? "UNAVAILABLE"
                : value.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String rate(long numerator, long denominator) {
        return denominator == 0 ? "UNAVAILABLE" : format((double) numerator * 100 / denominator) + "%";
    }

    private static long percentile(List<Long> values, double quantile) {
        List<Long> sorted = values.stream().sorted().toList();
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = Math.min(sorted.size() - 1,
                Math.max(0, (int) Math.ceil(sorted.size() * quantile) - 1));
        return sorted.get(index);
    }

    private static String averageIntegers(List<Integer> values) {
        List<Integer> available = values.stream().filter(value -> value != null).toList();
        return available.isEmpty() ? "UNAVAILABLE"
                : format(available.stream().mapToInt(Integer::intValue).average().orElse(0));
    }

    private static String percentileIntegers(List<Integer> values, double quantile) {
        List<Integer> available = values.stream().filter(value -> value != null).sorted().toList();
        if (available.isEmpty()) {
            return "UNAVAILABLE";
        }
        int index = Math.min(available.size() - 1,
                Math.max(0, (int) Math.ceil(available.size() * quantile) - 1));
        return available.get(index).toString();
    }

    private static String maxIntegers(List<Integer> values) {
        return values.stream().filter(value -> value != null).max(Integer::compareTo)
                .map(Object::toString).orElse("UNAVAILABLE");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("Benchmark precondition failed: " + message);
        }
    }

    static boolean benchmarkEnabled() {
        return Boolean.getBoolean("code.rag.selector.client-ab.enabled");
    }

    private enum ClientType {
        SPRING_AI,
        DEEPSEEK_HTTP
    }

    private record QueryCase(String id, String query) {
    }

    private record FrozenCandidates(List<CodeEvidenceCandidateCard> cards, String fingerprint) {
    }

    private record EvidenceRef(String localId, String chunkId, String file, String symbol, String chunkType) {
    }

    private record Observation(String timestamp,
                               int sequenceIndex,
                               String queryId,
                               String query,
                               int run,
                               ClientType clientType,
                               int candidateCount,
                               String candidateFingerprint,
                               long latencyMs,
                               Boolean reasoningContentPresent,
                               Integer reasoningContentChars,
                               Integer promptTokens,
                               Integer completionTokens,
                               Integer totalTokens,
                               boolean usageAvailable,
                               String finishReason,
                               boolean timeout,
                               boolean fallback,
                               String fallbackReason,
                               boolean jsonParseOk,
                               List<String> proposedCandidateIds,
                               List<String> effectiveSelectedCandidateIds,
                               List<String> selectedChunkIds,
                               List<EvidenceRef> selectedEvidence) {
        static Observation from(int sequenceIndex,
                                QueryCase queryCase,
                                int run,
                                ClientType clientType,
                                FrozenCandidates frozen,
                                CodeEvidenceSelectionResult result) {
            List<String> selectedChunkIds = immutable(result.getSelectedChunkIds());
            Map<String, Integer> indexByChunkId = new LinkedHashMap<>();
            for (int i = 0; i < frozen.cards().size(); i++) {
                indexByChunkId.putIfAbsent(frozen.cards().get(i).getChunkId(), i);
            }
            List<String> effectiveIds = selectedChunkIds.stream()
                    .map(indexByChunkId::get)
                    .filter(index -> index != null)
                    .map(CodeRagSelectorClientAbBenchmarkTest::localId)
                    .toList();
            List<EvidenceRef> evidence = selectedChunkIds.stream()
                    .map(indexByChunkId::get)
                    .filter(index -> index != null)
                    .map(index -> {
                        CodeEvidenceCandidateCard card = frozen.cards().get(index);
                        return new EvidenceRef(localId(index), card.getChunkId(), card.getFilePath(),
                                card.getSymbolName(), card.getChunkType());
                    })
                    .toList();
            String reason = result.getReason();
            return new Observation(OffsetDateTime.now().toString(), sequenceIndex, queryCase.id(),
                    queryCase.query(), run, clientType, frozen.cards().size(), frozen.fingerprint(),
                    result.getLatencyMs(), result.getReasoningContentPresent(), result.getReasoningContentChars(),
                    result.getPromptTokens(), result.getCompletionTokens(), result.getTotalTokens(),
                    result.isUsageAvailable(), result.getFinishReason(),
                    StringUtils.hasText(reason) && reason.startsWith("SELECTOR_TIMEOUT:"),
                    result.isFallback(), reason, result.isJsonParseOk(),
                    immutable(result.getProposedCandidateIds()), effectiveIds, selectedChunkIds, evidence);
        }

        private static List<String> immutable(List<String> values) {
            return values == null ? List.of() : List.copyOf(values);
        }
    }

    private record Comparison(String queryId,
                              int run,
                              boolean exactMatch,
                              double jaccard,
                              List<String> baselineIds,
                              List<String> experimentIds) {
    }

    private record Summary(int calls,
                           long success,
                           long timeout,
                           long fallback,
                           double meanLatency,
                           long p50Latency,
                           long p95Latency,
                           long maxLatency,
                           long usageAvailable,
                           String meanCompletion,
                           String p50Completion,
                           String maxCompletion,
                           String meanReasoning,
                           String p50Reasoning,
                           String maxReasoning) {
        static Summary from(List<Observation> observations) {
            List<Long> latencies = observations.stream().map(Observation::latencyMs).toList();
            return new Summary(observations.size(),
                    observations.stream().filter(observation -> !observation.fallback()).count(),
                    observations.stream().filter(Observation::timeout).count(),
                    observations.stream().filter(Observation::fallback).count(),
                    latencies.stream().mapToLong(Long::longValue).average().orElse(0),
                    percentile(latencies, 0.50), percentile(latencies, 0.95),
                    latencies.stream().mapToLong(Long::longValue).max().orElse(0),
                    observations.stream().filter(Observation::usageAvailable).count(),
                    averageIntegers(observations.stream().map(Observation::completionTokens).toList()),
                    percentileIntegers(observations.stream().map(Observation::completionTokens).toList(), 0.50),
                    maxIntegers(observations.stream().map(Observation::completionTokens).toList()),
                    averageIntegers(observations.stream().map(Observation::reasoningContentChars).toList()),
                    percentileIntegers(observations.stream().map(Observation::reasoningContentChars).toList(), 0.50),
                    maxIntegers(observations.stream().map(Observation::reasoningContentChars).toList()));
        }

        String row(ClientType type) {
            return "| " + type + " | " + calls + " | " + success + " | " + timeout + " | "
                    + rate(timeout, calls) + " | " + fallback + " | " + rate(fallback, calls) + " | "
                    + format(meanLatency) + " | " + p50Latency + " | " + p95Latency + " | " + maxLatency
                    + " | " + usageAvailable + " | " + meanCompletion + " | " + p50Completion + " | "
                    + maxCompletion + " | " + meanReasoning + " | " + p50Reasoning + " | "
                    + maxReasoning + " |";
        }
    }
}
