package com.kama.jchatmind.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.mapper.CodeRepositoryMapper;
import com.kama.jchatmind.model.dto.CodeAnswerEvidenceResult;
import com.kama.jchatmind.model.dto.CodeRagExecutionResult;
import com.kama.jchatmind.model.dto.CodeSearchExecutionResult;
import com.kama.jchatmind.model.dto.CodeSearchResult;
import com.kama.jchatmind.model.entity.CodeRepository;
import com.kama.jchatmind.service.CodeSearchService;
import com.kama.jchatmind.service.impl.CodeLlmEvidenceSelector;
import com.kama.jchatmind.service.impl.CodeRagAnswerEvidenceServiceImpl;
import com.kama.jchatmind.service.impl.DeepSeekLlmSelectorClient;
import com.kama.jchatmind.service.impl.SpringAiLlmSelectorClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Real 80-case evidence-selection quality A/B evaluation. Disabled unless explicitly enabled.
 */
@Tag("rag-selector-quality-ab")
@EnabledIf("evaluationEnabled")
@SpringBootTest
class CodeRagSelectorClientQualityAbEvaluationTest {
    private static final String CASE_RESOURCE = "eval/code_rag_eval_cases.json";
    private static final String REPOSITORY_ID = "bf4ef891-330b-4ce8-9002-ba4c43ffe210";
    private static final String REPOSITORY_NAME = "FlashDeal";
    private static final String SELECTOR_REGISTRY_KEY = "deepseek-chat";
    private static final String PROVIDER_MODEL = "deepseek-v4-flash";
    private static final int FIXTURE_COUNT = 80;
    private static final int RAW_TOP_K = 20;
    private static final int FINAL_TOP_K = 5;
    private static final long TIMEOUT_MS = 30_000;
    private static final int EXPECTED_CALLS = FIXTURE_COUNT * 2;
    private static final Path OUTPUT_DIR = Path.of("target", "eval");
    private static final Path CSV_PATH = OUTPUT_DIR.resolve("code-rag-selector-client-quality-ab.csv");
    private static final Path REPORT_PATH = OUTPUT_DIR.resolve("code-rag-selector-client-quality-ab.md");

    private final CodeRagGroundTruthMatcher matcher = new CodeRagGroundTruthMatcher();

    @Autowired
    private CodeSearchService realCodeSearchService;

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
    void compareEvidenceSelectionQualityAcrossGoldenFixture() throws Exception {
        CodeRepository repository = requireFixedEnvironment();
        List<CodeRagEvalCase> cases = loadAndValidateCases();

        // Complete all fixture parsing and candidate freezing before the first provider call.
        Map<String, FrozenCandidates> frozenByCase = freezeAllCandidates(cases);
        CodeLlmEvidenceSelector baselineSelector = new CodeLlmEvidenceSelector(
                springAiClient, properties, objectMapper, selectorExecutor);
        DeepSeekLlmSelectorClient directClient = new DeepSeekLlmSelectorClient(
                restClientBuilder, objectMapper, providerBaseUrl, providerApiKey, providerModel);
        CodeLlmEvidenceSelector experimentSelector = new CodeLlmEvidenceSelector(
                directClient, properties, objectMapper, selectorExecutor);

        List<Observation> observations = new ArrayList<>(EXPECTED_CALLS);
        int sequenceIndex = 0;
        for (int caseIndex = 0; caseIndex < cases.size(); caseIndex++) {
            CodeRagEvalCase evalCase = cases.get(caseIndex);
            FrozenCandidates frozen = frozenByCase.get(evalCase.id);
            ClientType first = caseIndex % 2 == 0 ? ClientType.SPRING_AI : ClientType.DEEPSEEK_HTTP;
            ClientType second = first == ClientType.SPRING_AI
                    ? ClientType.DEEPSEEK_HTTP : ClientType.SPRING_AI;
            sequenceIndex = executeAndRecord(observations, ++sequenceIndex, evalCase, first,
                    selector(first, baselineSelector, experimentSelector), frozen);
            sequenceIndex = executeAndRecord(observations, ++sequenceIndex, evalCase, second,
                    selector(second, baselineSelector, experimentSelector), frozen);
        }

        require(observations.size() == EXPECTED_CALLS,
                "Evaluation must produce exactly " + EXPECTED_CALLS + " observations");
        writeCsv(observations);
        writeReport(repository, cases, frozenByCase, observations);
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

    private List<CodeRagEvalCase> loadAndValidateCases() throws IOException {
        ClassPathResource resource = new ClassPathResource(CASE_RESOURCE);
        List<CodeRagEvalCase> cases = objectMapper.readValue(resource.getInputStream(), new TypeReference<>() {
        });
        require(cases.size() == FIXTURE_COUNT, "fixture count must be exactly " + FIXTURE_COUNT);
        Map<String, Boolean> ids = new LinkedHashMap<>();
        for (CodeRagEvalCase evalCase : cases) {
            require(evalCase != null && evalCase.hasValidGroundTruth(),
                    "invalid Code RAG ground truth fixture");
            require(ids.put(evalCase.id, Boolean.TRUE) == null, "duplicate fixture id: " + evalCase.id);
        }
        return List.copyOf(cases);
    }

    private Map<String, FrozenCandidates> freezeAllCandidates(List<CodeRagEvalCase> cases) {
        Map<String, FrozenCandidates> frozenByCase = new LinkedHashMap<>();
        for (CodeRagEvalCase evalCase : cases) {
            CodeSearchExecutionResult search = realCodeSearchService.searchWithTrace(
                    REPOSITORY_ID, evalCase.query, RAW_TOP_K);
            require(search != null && search.getCandidates() != null,
                    evalCase.id + " retrieval returned no candidate list");
            require(search.getCandidates().size() == RAW_TOP_K,
                    evalCase.id + " candidate count must be exactly " + RAW_TOP_K);
            List<CodeSearchResult> candidates = List.copyOf(search.getCandidates());
            String fingerprint = fingerprint(candidates);
            require(StringUtils.hasText(fingerprint), evalCase.id + " candidate fingerprint is missing");
            int goldenRawRank = matcher.firstMatchRank(candidates, evalCase);
            frozenByCase.put(evalCase.id, new FrozenCandidates(candidates, fingerprint, goldenRawRank,
                    search.getEmbeddingLatencyMs(), search.getRetrievalLatencyMs(), search.isCacheHit()));
        }
        require(frozenByCase.size() == FIXTURE_COUNT, "Not all fixture candidates were frozen");
        return Map.copyOf(frozenByCase);
    }

    private int executeAndRecord(List<Observation> observations,
                                 int sequenceIndex,
                                 CodeRagEvalCase evalCase,
                                 ClientType clientType,
                                 CodeLlmEvidenceSelector selector,
                                 FrozenCandidates frozen) {
        assertFingerprint(evalCase.id, sequenceIndex, frozen, "before");
        CodeRagAnswerEvidenceServiceImpl service = new CodeRagAnswerEvidenceServiceImpl(
                new FrozenCodeSearchService(evalCase.query, frozen), selector, properties);
        CodeRagExecutionResult execution = service.execute(REPOSITORY_ID, evalCase.query);
        assertFingerprint(evalCase.id, sequenceIndex, frozen, "after");
        require(execution != null && execution.getAnswerEvidence() != null,
                "Code RAG returned no answer evidence for " + evalCase.id);

        Observation observation = Observation.from(
                sequenceIndex, evalCase, clientType, frozen, execution, matcher);
        if (isFatalInfrastructureFailure(observation.fallbackReason())) {
            throw new IllegalStateException("Provider infrastructure failure at sequence " + sequenceIndex
                    + ": " + observation.fallbackReason());
        }
        observations.add(observation);
        return sequenceIndex;
    }

    private void assertFingerprint(String caseId,
                                   int sequenceIndex,
                                   FrozenCandidates frozen,
                                   String phase) {
        require(frozen.fingerprint().equals(fingerprint(frozen.candidates())),
                caseId + " candidate data changed " + phase + " sequence " + sequenceIndex);
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

    private String fingerprint(List<CodeSearchResult> candidates) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < candidates.size(); i++) {
                CodeSearchResult candidate = candidates.get(i);
                updateDigest(digest, localId(i));
                updateDigest(digest, candidateId(candidate, i));
                updateDigest(digest, candidate.getFilePath());
                updateDigest(digest, candidate.getSymbolName());
                updateDigest(digest, candidate.getChunkType());
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
                "timestamp,sequence_index,fixture_id,query,category,difficulty,client_type,candidate_count,candidate_fingerprint,golden,golden_in_raw_top_k,golden_raw_rank,latency_ms,timeout,fallback,fallback_reason,json_parse_ok,empty_selection,invalid_candidate_id,json_parse_error,model_error,proposed_candidate_ids,effective_selected_candidate_ids,selector_only_selected_candidate_ids,selected_files,selected_symbols,selected_chunk_types,selected_at_1,selected_at_3,selected_at_5,selector_only_selected_at_1,selector_only_selected_at_3,selector_only_selected_at_5,reasoning_content_chars,completion_tokens,usage_available\n");
        for (Observation observation : observations) {
            csv.append(observation.timestamp()).append(',')
                    .append(observation.sequenceIndex()).append(',')
                    .append(observation.fixtureId()).append(',')
                    .append(csv(observation.query())).append(',')
                    .append(observation.category()).append(',')
                    .append(observation.difficulty()).append(',')
                    .append(observation.clientType()).append(',')
                    .append(observation.candidateCount()).append(',')
                    .append(observation.candidateFingerprint()).append(',')
                    .append(csv(observation.golden())).append(',')
                    .append(observation.goldenInRawTopK()).append(',')
                    .append(observation.goldenRawRank()).append(',')
                    .append(observation.latencyMs()).append(',')
                    .append(observation.timeout()).append(',')
                    .append(observation.fallback()).append(',')
                    .append(csv(observation.fallbackReason())).append(',')
                    .append(observation.jsonParseOk()).append(',')
                    .append(observation.emptySelection()).append(',')
                    .append(observation.invalidCandidateId()).append(',')
                    .append(observation.jsonParseError()).append(',')
                    .append(observation.modelError()).append(',')
                    .append(csv(json(observation.proposedCandidateIds()))).append(',')
                    .append(csv(json(observation.effectiveSelectedCandidateIds()))).append(',')
                    .append(csv(json(observation.selectorOnlySelectedCandidateIds()))).append(',')
                    .append(csv(json(observation.selectedEvidence().stream().map(EvidenceRef::file).toList()))).append(',')
                    .append(csv(json(observation.selectedEvidence().stream().map(EvidenceRef::symbol).toList()))).append(',')
                    .append(csv(json(observation.selectedEvidence().stream().map(EvidenceRef::chunkType).toList()))).append(',')
                    .append(observation.selectedAt1()).append(',')
                    .append(observation.selectedAt3()).append(',')
                    .append(observation.selectedAt5()).append(',')
                    .append(observation.selectorOnlySelectedAt1()).append(',')
                    .append(observation.selectorOnlySelectedAt3()).append(',')
                    .append(observation.selectorOnlySelectedAt5()).append(',')
                    .append(nullable(observation.reasoningContentChars())).append(',')
                    .append(nullable(observation.completionTokens())).append(',')
                    .append(observation.usageAvailable()).append('\n');
        }
        Files.writeString(CSV_PATH, csv.toString(), StandardCharsets.UTF_8);
    }

    private void writeReport(CodeRepository repository,
                             List<CodeRagEvalCase> cases,
                             Map<String, FrozenCandidates> frozenByCase,
                             List<Observation> observations) throws IOException {
        Summary baseline = Summary.from(group(observations, ClientType.SPRING_AI));
        Summary experiment = Summary.from(group(observations, ClientType.DEEPSEEK_HTTP));
        List<CaseComparison> comparisons = comparisons(cases, observations);

        StringBuilder report = new StringBuilder("# Code RAG Selector Client Quality A/B Evaluation\n\n")
                .append("- timestamp: ").append(OffsetDateTime.now()).append('\n')
                .append("- repository: ").append(repository.getName()).append(" (")
                .append(repository.getId()).append(")\n")
                .append("- comparison: DEEPSEEK_HTTP + thinking.disabled + retry=0 vs current SPRING_AI baseline\n")
                .append("- provider model: ").append(providerModel).append('\n')
                .append("- rawTopK: ").append(RAW_TOP_K).append('\n')
                .append("- finalTopK: ").append(FINAL_TOP_K).append('\n')
                .append("- timeout-ms: ").append(TIMEOUT_MS).append('\n')
                .append("- fixture count: ").append(cases.size()).append('\n')
                .append("- calls: ").append(observations.size()).append('\n')
                .append("- execution: sequential, concurrency=1, alternating client order per fixture\n")
                .append("- historical snapshot: selected@1=72/80, selected@3=77/80, selected@5=78/80; current A/B is authoritative\n")
                .append("- attribution boundary: client transport and retry semantics differ in addition to thinking mode\n\n")
                .append("## Metric Semantics\n\n")
                .append("The existing CodeRagGroundTruthMatcher is reused unchanged. A match first satisfies expectedChunkTypes, then matches expected file keywords or expected symbol keywords across symbolName/apiPath/contentPreview/metadata. ")
                .append("Effective selected@K includes RAW_VECTOR fallback. Selector-only selected@K uses selectorValidChunkIds and excludes fallback evidence.\n\n")
                .append("## Core Quality Results\n\n")
                .append("| Client | selected@1 | selected@3 | selected@5 | selector-only@1 | selector-only@3 | selector-only@5 | timeout | fallback | success |\n")
                .append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n")
                .append(baseline.qualityRow(ClientType.SPRING_AI)).append('\n')
                .append(experiment.qualityRow(ClientType.DEEPSEEK_HTTP)).append('\n')
                .append("| A→B delta | ").append(delta(experiment.selectedAt1(), baseline.selectedAt1())).append(" | ")
                .append(delta(experiment.selectedAt3(), baseline.selectedAt3())).append(" | ")
                .append(delta(experiment.selectedAt5(), baseline.selectedAt5())).append(" | ")
                .append(delta(experiment.selectorOnlyAt1(), baseline.selectorOnlyAt1())).append(" | ")
                .append(delta(experiment.selectorOnlyAt3(), baseline.selectorOnlyAt3())).append(" | ")
                .append(delta(experiment.selectorOnlyAt5(), baseline.selectorOnlyAt5())).append(" | ")
                .append(delta(experiment.timeout(), baseline.timeout())).append(" | ")
                .append(delta(experiment.fallback(), baseline.fallback())).append(" | ")
                .append(delta(experiment.success(), baseline.success())).append(" |\n");

        report.append("\n## Failure Diagnostics\n\n")
                .append("| Client | EMPTY_SELECTION | INVALID_CANDIDATE_ID | JSON_PARSE_ERROR | MODEL_ERROR |\n")
                .append("| --- | ---: | ---: | ---: | ---: |\n")
                .append(baseline.failureRow(ClientType.SPRING_AI)).append('\n')
                .append(experiment.failureRow(ClientType.DEEPSEEK_HTTP)).append('\n');

        report.append("\n## Case Matrix (selected@1)\n\n")
                .append("| Matrix | Count |\n| --- | ---: |\n")
                .append("| A hit / B hit | ").append(countMatrix(comparisons, true, true)).append(" |\n")
                .append("| A hit / B miss | ").append(countMatrix(comparisons, true, false)).append(" |\n")
                .append("| A miss / B hit | ").append(countMatrix(comparisons, false, true)).append(" |\n")
                .append("| A miss / B miss | ").append(countMatrix(comparisons, false, false)).append(" |\n");

        appendChangedCases(report, "Regressions: A hit / B miss", comparisons, true, false);
        appendChangedCases(report, "Improvements: A miss / B hit", comparisons, false, true);

        long retrievalMisses = frozenByCase.values().stream()
                .filter(frozen -> frozen.goldenRawRank() == 0).count();
        report.append("\n## Retrieval vs Selector\n\n")
                .append("- golden absent from frozen rawTopK=20: ").append(retrievalMisses).append('\n')
                .append("- golden present in rawTopK but SPRING_AI effective selector missed: ")
                .append(selectorMissCount(observations, ClientType.SPRING_AI)).append('\n')
                .append("- golden present in rawTopK but DEEPSEEK_HTTP effective selector missed: ")
                .append(selectorMissCount(observations, ClientType.DEEPSEEK_HTTP)).append('\n')
                .append("- selector-only misses exclude fallback evidence; see selector-only@K above.\n");

        report.append("\n## Auxiliary Performance\n\n")
                .append("| Client | Mean ms | P50 ms | P95 ms | reasoning chars mean/P50/max | completion tokens mean/P50/max | usage available |\n")
                .append("| --- | ---: | ---: | ---: | --- | --- | ---: |\n")
                .append(baseline.performanceRow(ClientType.SPRING_AI)).append('\n')
                .append(experiment.performanceRow(ClientType.DEEPSEEK_HTTP)).append('\n');

        report.append("\n## Candidate Fingerprints\n\n")
                .append("| Fixture | Count | Golden raw rank | SHA-256 |\n")
                .append("| --- | ---: | ---: | --- |\n");
        for (CodeRagEvalCase evalCase : cases) {
            FrozenCandidates frozen = frozenByCase.get(evalCase.id);
            report.append("| ").append(evalCase.id).append(" | ")
                    .append(frozen.candidates().size()).append(" | ")
                    .append(frozen.goldenRawRank()).append(" | `")
                    .append(frozen.fingerprint()).append("` |\n");
        }

        Files.createDirectories(OUTPUT_DIR);
        Files.writeString(REPORT_PATH, report.toString(), StandardCharsets.UTF_8);
    }

    private void appendChangedCases(StringBuilder report,
                                    String title,
                                    List<CaseComparison> comparisons,
                                    boolean baselineHit,
                                    boolean experimentHit) {
        report.append("\n## ").append(title).append("\n\n");
        List<CaseComparison> changed = comparisons.stream()
                .filter(comparison -> comparison.baseline().selectedAt1() == baselineHit)
                .filter(comparison -> comparison.experiment().selectedAt1() == experimentHit)
                .toList();
        if (changed.isEmpty()) {
            report.append("None.\n");
            return;
        }
        for (CaseComparison comparison : changed) {
            Observation baseline = comparison.baseline();
            Observation experiment = comparison.experiment();
            report.append("\n### ").append(baseline.fixtureId()).append("\n\n")
                    .append("- query: ").append(baseline.query()).append('\n')
                    .append("- golden: ").append(baseline.golden()).append('\n')
                    .append("- goldenInRawTopK: ").append(baseline.goldenInRawTopK()).append('\n')
                    .append("- goldenRawRank: ").append(baseline.goldenRawRank()).append('\n')
                    .append("- A proposedCandidateIds: ").append(baseline.proposedCandidateIds()).append('\n')
                    .append("- A effectiveCandidateIds: ").append(baseline.effectiveSelectedCandidateIds()).append('\n')
                    .append("- A evidence: ").append(evidenceText(baseline.selectedEvidence())).append('\n')
                    .append("- A fallback: ").append(baseline.fallback()).append("; reason=")
                    .append(table(baseline.fallbackReason())).append('\n')
                    .append("- B proposedCandidateIds: ").append(experiment.proposedCandidateIds()).append('\n')
                    .append("- B effectiveCandidateIds: ").append(experiment.effectiveSelectedCandidateIds()).append('\n')
                    .append("- B evidence: ").append(evidenceText(experiment.selectedEvidence())).append('\n')
                    .append("- B fallback: ").append(experiment.fallback()).append("; reason=")
                    .append(table(experiment.fallbackReason())).append('\n');
        }
    }

    private List<CaseComparison> comparisons(List<CodeRagEvalCase> cases, List<Observation> observations) {
        List<CaseComparison> comparisons = new ArrayList<>(cases.size());
        for (CodeRagEvalCase evalCase : cases) {
            comparisons.add(new CaseComparison(
                    find(observations, evalCase.id, ClientType.SPRING_AI),
                    find(observations, evalCase.id, ClientType.DEEPSEEK_HTTP)));
        }
        return comparisons;
    }

    private Observation find(List<Observation> observations, String fixtureId, ClientType clientType) {
        return observations.stream()
                .filter(observation -> observation.fixtureId().equals(fixtureId))
                .filter(observation -> observation.clientType() == clientType)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Missing observation for " + fixtureId + " client " + clientType));
    }

    private List<Observation> group(List<Observation> observations, ClientType clientType) {
        return observations.stream().filter(observation -> observation.clientType() == clientType).toList();
    }

    private long countMatrix(List<CaseComparison> comparisons, boolean baselineHit, boolean experimentHit) {
        return comparisons.stream()
                .filter(comparison -> comparison.baseline().selectedAt1() == baselineHit)
                .filter(comparison -> comparison.experiment().selectedAt1() == experimentHit)
                .count();
    }

    private long selectorMissCount(List<Observation> observations, ClientType type) {
        return observations.stream()
                .filter(observation -> observation.clientType() == type)
                .filter(Observation::goldenInRawTopK)
                .filter(observation -> !observation.selectedAt5())
                .count();
    }

    private static String candidateId(CodeSearchResult candidate, int index) {
        return StringUtils.hasText(candidate.getChunkId()) ? candidate.getChunkId() : "candidate-" + index;
    }

    private static String localId(int zeroBasedIndex) {
        return String.format(Locale.ROOT, "C%02d", zeroBasedIndex + 1);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize quality observation", e);
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
        return denominator == 0 ? "UNAVAILABLE" : numerator + "/" + denominator
                + " (" + format((double) numerator * 100 / denominator) + "%)";
    }

    private static String delta(long experiment, long baseline) {
        long absolute = experiment - baseline;
        return (absolute >= 0 ? "+" : "") + absolute + " ("
                + (absolute >= 0 ? "+" : "") + format((double) absolute * 100 / FIXTURE_COUNT) + " pp)";
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
            throw new IllegalStateException("Quality A/B precondition failed: " + message);
        }
    }

    static boolean evaluationEnabled() {
        return Boolean.getBoolean("code.rag.selector.quality-ab.enabled");
    }

    private enum ClientType {
        SPRING_AI,
        DEEPSEEK_HTTP
    }

    private record FrozenCandidates(List<CodeSearchResult> candidates,
                                    String fingerprint,
                                    int goldenRawRank,
                                    long embeddingLatencyMs,
                                    long retrievalLatencyMs,
                                    boolean cacheHit) {
    }

    private record EvidenceRef(String localId, String chunkId, String file, String symbol, String chunkType) {
    }

    private record Observation(String timestamp,
                               int sequenceIndex,
                               String fixtureId,
                               String query,
                               String category,
                               String difficulty,
                               ClientType clientType,
                               int candidateCount,
                               String candidateFingerprint,
                               String golden,
                               boolean goldenInRawTopK,
                               int goldenRawRank,
                               long latencyMs,
                               boolean timeout,
                               boolean fallback,
                               String fallbackReason,
                               boolean jsonParseOk,
                               boolean emptySelection,
                               boolean invalidCandidateId,
                               boolean jsonParseError,
                               boolean modelError,
                               List<String> proposedCandidateIds,
                               List<String> effectiveSelectedCandidateIds,
                               List<String> selectorOnlySelectedCandidateIds,
                               List<EvidenceRef> selectedEvidence,
                               boolean selectedAt1,
                               boolean selectedAt3,
                               boolean selectedAt5,
                               boolean selectorOnlySelectedAt1,
                               boolean selectorOnlySelectedAt3,
                               boolean selectorOnlySelectedAt5,
                               Integer reasoningContentChars,
                               Integer completionTokens,
                               boolean usageAvailable) {
        static Observation from(int sequenceIndex,
                                CodeRagEvalCase evalCase,
                                ClientType clientType,
                                FrozenCandidates frozen,
                                CodeRagExecutionResult execution,
                                CodeRagGroundTruthMatcher matcher) {
            CodeAnswerEvidenceResult answer = execution.getAnswerEvidence();
            List<CodeSearchResult> effective = answer.getSelectedEvidence() == null
                    ? List.of() : List.copyOf(answer.getSelectedEvidence());
            List<CodeSearchResult> selectorOnly = resultsForChunkIds(
                    frozen.candidates(), execution.getSelectorValidChunkIds());
            List<String> effectiveIds = localIdsForResults(frozen.candidates(), effective);
            List<String> selectorOnlyIds = localIdsForResults(frozen.candidates(), selectorOnly);
            List<EvidenceRef> evidence = evidenceRefs(frozen.candidates(), effective);
            int effectiveRank = matcher.firstMatchRank(effective, evalCase);
            int selectorOnlyRank = matcher.firstMatchRank(selectorOnly, evalCase);
            String reason = execution.getSelectorFallbackReason();
            return new Observation(OffsetDateTime.now().toString(), sequenceIndex, evalCase.id, evalCase.query,
                    evalCase.category, evalCase.difficulty, clientType, frozen.candidates().size(),
                    frozen.fingerprint(), evalCase.groundTruthDescription(), frozen.goldenRawRank() > 0,
                    frozen.goldenRawRank(), execution.getSelectorLatencyMs(), startsWith(reason, "SELECTOR_TIMEOUT:"),
                    answer.isFallback(), reason, answer.isJsonParseOk(),
                    execution.isEmptySelectorResult() || startsWith(reason, "EMPTY_SELECTION:"),
                    startsWith(reason, "INVALID_CANDIDATE_ID:"), startsWith(reason, "JSON_PARSE_ERROR:"),
                    startsWith(reason, "MODEL_ERROR:"), safe(execution.getSelectorProposedCandidateIds()),
                    effectiveIds, selectorOnlyIds, evidence,
                    hitAt(effectiveRank, 1), hitAt(effectiveRank, 3), hitAt(effectiveRank, 5),
                    hitAt(selectorOnlyRank, 1), hitAt(selectorOnlyRank, 3), hitAt(selectorOnlyRank, 5),
                    execution.getSelectorReasoningContentChars(), execution.getSelectorCompletionTokens(),
                    execution.isSelectorUsageAvailable());
        }

        private static List<CodeSearchResult> resultsForChunkIds(List<CodeSearchResult> candidates,
                                                                 List<String> chunkIds) {
            if (chunkIds == null || chunkIds.isEmpty()) {
                return List.of();
            }
            List<CodeSearchResult> results = new ArrayList<>();
            for (String chunkId : chunkIds) {
                for (int i = 0; i < candidates.size(); i++) {
                    if (candidateId(candidates.get(i), i).equals(chunkId)) {
                        results.add(candidates.get(i));
                        break;
                    }
                }
                if (results.size() >= FINAL_TOP_K) {
                    break;
                }
            }
            return List.copyOf(results);
        }

        private static List<String> localIdsForResults(List<CodeSearchResult> candidates,
                                                       List<CodeSearchResult> selected) {
            List<String> ids = new ArrayList<>();
            for (CodeSearchResult result : selected) {
                for (int i = 0; i < candidates.size(); i++) {
                    CodeSearchResult candidate = candidates.get(i);
                    if (candidate == result || (StringUtils.hasText(result.getChunkId())
                            && result.getChunkId().equals(candidate.getChunkId()))) {
                        ids.add(localId(i));
                        break;
                    }
                }
            }
            return List.copyOf(ids);
        }

        private static List<EvidenceRef> evidenceRefs(List<CodeSearchResult> candidates,
                                                      List<CodeSearchResult> selected) {
            List<String> localIds = localIdsForResults(candidates, selected);
            List<EvidenceRef> refs = new ArrayList<>();
            for (int i = 0; i < selected.size(); i++) {
                CodeSearchResult result = selected.get(i);
                refs.add(new EvidenceRef(localIds.get(i), result.getChunkId(), result.getFilePath(),
                        result.getSymbolName(), result.getChunkType()));
            }
            return List.copyOf(refs);
        }

        private static boolean startsWith(String value, String prefix) {
            return value != null && value.startsWith(prefix);
        }

        private static boolean hitAt(int rank, int k) {
            return rank > 0 && rank <= k;
        }

        private static <T> List<T> safe(List<T> values) {
            return values == null ? List.of() : List.copyOf(values);
        }
    }

    private record CaseComparison(Observation baseline, Observation experiment) {
    }

    private record Summary(int calls,
                           long selectedAt1,
                           long selectedAt3,
                           long selectedAt5,
                           long selectorOnlyAt1,
                           long selectorOnlyAt3,
                           long selectorOnlyAt5,
                           long timeout,
                           long fallback,
                           long success,
                           long emptySelection,
                           long invalidCandidateId,
                           long jsonParseError,
                           long modelError,
                           double meanLatency,
                           long p50Latency,
                           long p95Latency,
                           String reasoningMean,
                           String reasoningP50,
                           String reasoningMax,
                           String completionMean,
                           String completionP50,
                           String completionMax,
                           long usageAvailable) {
        static Summary from(List<Observation> observations) {
            List<Long> latencies = observations.stream().map(Observation::latencyMs).toList();
            return new Summary(observations.size(),
                    observations.stream().filter(Observation::selectedAt1).count(),
                    observations.stream().filter(Observation::selectedAt3).count(),
                    observations.stream().filter(Observation::selectedAt5).count(),
                    observations.stream().filter(Observation::selectorOnlySelectedAt1).count(),
                    observations.stream().filter(Observation::selectorOnlySelectedAt3).count(),
                    observations.stream().filter(Observation::selectorOnlySelectedAt5).count(),
                    observations.stream().filter(Observation::timeout).count(),
                    observations.stream().filter(Observation::fallback).count(),
                    observations.stream().filter(observation -> !observation.fallback()).count(),
                    observations.stream().filter(Observation::emptySelection).count(),
                    observations.stream().filter(Observation::invalidCandidateId).count(),
                    observations.stream().filter(Observation::jsonParseError).count(),
                    observations.stream().filter(Observation::modelError).count(),
                    latencies.stream().mapToLong(Long::longValue).average().orElse(0),
                    percentile(latencies, 0.50), percentile(latencies, 0.95),
                    averageIntegers(observations.stream().map(Observation::reasoningContentChars).toList()),
                    percentileIntegers(observations.stream().map(Observation::reasoningContentChars).toList(), 0.50),
                    maxIntegers(observations.stream().map(Observation::reasoningContentChars).toList()),
                    averageIntegers(observations.stream().map(Observation::completionTokens).toList()),
                    percentileIntegers(observations.stream().map(Observation::completionTokens).toList(), 0.50),
                    maxIntegers(observations.stream().map(Observation::completionTokens).toList()),
                    observations.stream().filter(Observation::usageAvailable).count());
        }

        String qualityRow(ClientType type) {
            return "| " + type + " | " + rate(selectedAt1, calls) + " | " + rate(selectedAt3, calls)
                    + " | " + rate(selectedAt5, calls) + " | " + rate(selectorOnlyAt1, calls) + " | "
                    + rate(selectorOnlyAt3, calls) + " | " + rate(selectorOnlyAt5, calls) + " | "
                    + rate(timeout, calls) + " | " + rate(fallback, calls) + " | " + rate(success, calls) + " |";
        }

        String failureRow(ClientType type) {
            return "| " + type + " | " + emptySelection + " | " + invalidCandidateId + " | "
                    + jsonParseError + " | " + modelError + " |";
        }

        String performanceRow(ClientType type) {
            return "| " + type + " | " + format(meanLatency) + " | " + p50Latency + " | " + p95Latency
                    + " | " + reasoningMean + "/" + reasoningP50 + "/" + reasoningMax
                    + " | " + completionMean + "/" + completionP50 + "/" + completionMax
                    + " | " + usageAvailable + " |";
        }
    }

    private static final class FrozenCodeSearchService implements CodeSearchService {
        private final String expectedQuery;
        private final FrozenCandidates frozen;

        private FrozenCodeSearchService(String expectedQuery, FrozenCandidates frozen) {
            this.expectedQuery = expectedQuery;
            this.frozen = frozen;
        }

        @Override
        public List<CodeSearchResult> search(String repoId, String query, int topK) {
            return searchWithTrace(repoId, query, topK).getCandidates();
        }

        @Override
        public CodeSearchExecutionResult searchWithTrace(String repoId, String query, int topK) {
            require(REPOSITORY_ID.equals(repoId), "frozen search repoId changed");
            require(expectedQuery.equals(query), "frozen search query changed");
            require(topK == RAW_TOP_K, "frozen search topK changed");
            return CodeSearchExecutionResult.builder()
                    .candidates(frozen.candidates())
                    .embeddingLatencyMs(frozen.embeddingLatencyMs())
                    .retrievalLatencyMs(frozen.retrievalLatencyMs())
                    .cacheHit(frozen.cacheHit())
                    .build();
        }
    }
}
