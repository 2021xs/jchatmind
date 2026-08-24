package com.kama.jchatmind.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.mapper.CodeRepositoryMapper;
import com.kama.jchatmind.model.dto.CodeAnswerEvidenceResult;
import com.kama.jchatmind.model.dto.CodeRagExecutionResult;
import com.kama.jchatmind.model.dto.CodeSearchResult;
import com.kama.jchatmind.model.entity.CodeRepository;
import com.kama.jchatmind.service.CodeRagAnswerEvidenceService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Tag("rag-eval")
@EnabledIf("hasEvalRepoId")
@SpringBootTest
class CodeRagFinalEvaluationTest {
    private static final String CASE_RESOURCE = "eval/code_rag_eval_cases.json";
    private static final Path DEFAULT_OUTPUT_DIRECTORY = Path.of("target", "eval");

    @Autowired
    private CodeRagAnswerEvidenceService answerEvidenceService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CodeRepositoryMapper codeRepositoryMapper;

    @Autowired
    private CodeRagProperties properties;

    @Autowired
    private Environment environment;

    private final CodeRagGroundTruthMatcher matcher = new CodeRagGroundTruthMatcher();
    private final CodeRagFailureClassifier failureClassifier = new CodeRagFailureClassifier();
    private final CodeRagEvaluationReportWriter reportWriter = new CodeRagEvaluationReportWriter();

    @Test
    void evaluateLayeredCodeRagMainline() throws Exception {
        String repoId = configuredRepoId();
        List<CodeRagEvalCase> cases = selectedCases(loadCases());
        validateCases(cases);

        List<CodeRagEvalCaseResult> results = new ArrayList<>();
        for (CodeRagEvalCase evalCase : cases) {
            CodeRagExecutionResult execution = answerEvidenceService.execute(repoId, evalCase.query);
            CodeAnswerEvidenceResult answer = execution.getAnswerEvidence();
            if (answer == null) {
                throw new IllegalStateException("Code RAG returned no answer evidence: caseId=" + evalCase.id);
            }
            if (execution.isSelectorExecutionError()) {
                throw new IllegalStateException("Selector execution failed: caseId=" + evalCase.id
                        + ", reason=" + execution.getSelectorFallbackReason());
            }

            List<CodeSearchResult> rawCandidates = safeList(execution.getRawCandidates());
            List<CodeSearchResult> selectedEvidence = safeList(answer.getSelectedEvidence());
            int rawRank = matcher.firstMatchRank(rawCandidates, evalCase);
            int selectedRank = matcher.firstMatchRank(selectedEvidence, evalCase);
            CodeRagFailureType failureType = failureClassifier.classify(
                    true, false, false, answer.isFallback(), rawRank, selectedRank);
            results.add(new CodeRagEvalCaseResult(
                    evalCase, rawCandidates, selectedEvidence, rawRank, selectedRank, failureType,
                    answer.isFallback(), answer.isJsonParseOk(), execution.isCacheHit(),
                    execution.getSelectorProposedChunkIds(), execution.getSelectorValidChunkIds(),
                    execution.getSelectorInvalidChunkIds(),
                    execution.getSelectorProposedCandidateIds(), execution.getSelectorValidCandidateIds(),
                    execution.getSelectorInvalidCandidateIds(), execution.getSelectorFallbackReason(),
                    execution.isEmptySelectorResult(), "",
                    execution.getEmbeddingLatencyMs(), execution.getRetrievalLatencyMs(),
                    execution.getSelectorLatencyMs(), execution.getTotalLatencyMs(),
                    execution.getSelectorPromptTokens(), execution.getSelectorCompletionTokens(),
                    execution.getSelectorTotalTokens(), execution.isSelectorUsageAvailable(),
                    execution.getSelectorPromptChars(), execution.getSelectorCandidateSectionChars()));
            if (System.getProperty("eval.caseId") != null) {
                printDiagnostic(results.get(results.size() - 1));
            }
        }

        CodeRagEvaluationReportWriter.Environment reportEnvironment =
                new CodeRagEvaluationReportWriter.Environment(
                        OffsetDateTime.now(),
                        System.getProperty("java.version", "unknown"),
                        sanitizedDatabaseDescription(),
                        properties.getEmbeddingModel(),
                        properties.getLlmSelector().getModel(),
                        effectiveRawTopK(),
                        effectiveFinalTopK());
        Path outputDirectory = outputDirectory();
        reportWriter.write(outputDirectory, results, reportEnvironment);
        System.out.println("Code RAG layered evaluation completed: cases=" + results.size()
                + ", outputDirectory=" + outputDirectory.toAbsolutePath());
    }

    private List<CodeRagEvalCase> loadCases() throws IOException {
        ClassPathResource resource = new ClassPathResource(CASE_RESOURCE);
        return objectMapper.readValue(resource.getInputStream(), new TypeReference<>() {
        });
    }

    private List<CodeRagEvalCase> selectedCases(List<CodeRagEvalCase> cases) {
        String caseId = System.getProperty("eval.caseId");
        if (caseId != null && !caseId.isBlank()) {
            return cases.stream().filter(evalCase -> caseId.equals(evalCase.id)).toList();
        }
        int limit = Integer.getInteger("eval.limit", cases.size());
        return cases.subList(0, Math.min(Math.max(0, limit), cases.size()));
    }

    private void validateCases(List<CodeRagEvalCase> cases) {
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("No Code RAG evaluation cases selected");
        }
        for (CodeRagEvalCase evalCase : cases) {
            if (!evalCase.hasValidGroundTruth()) {
                throw new IllegalArgumentException("Invalid Code RAG ground truth: caseId=" + evalCase.id);
            }
        }
    }

    private String configuredRepoId() {
        String repoId = System.getProperty("eval.repoId");
        if (repoId == null || repoId.isBlank()) {
            repoId = System.getenv("CODE_RAG_EVAL_REPO_ID");
        }
        if ((repoId == null || repoId.isBlank()) && Boolean.getBoolean("eval.autoRepo")) {
            List<CodeRepository> repositories = codeRepositoryMapper.selectAll();
            if (!repositories.isEmpty()) {
                repoId = repositories.get(0).getId();
                System.out.println("Using latest code repository for evaluation: "
                        + repositories.get(0).getName() + " (" + repoId + ")");
            }
        }
        if (repoId == null || repoId.isBlank()) {
            throw new IllegalArgumentException("Code RAG evaluation repoId is not configured");
        }
        return repoId;
    }

    private String sanitizedDatabaseDescription() {
        String url = environment.getProperty("spring.datasource.url", "unknown");
        int queryIndex = url.indexOf('?');
        return queryIndex >= 0 ? url.substring(0, queryIndex) : url;
    }

    private int effectiveRawTopK() {
        int value = properties.getAnswerEvidence().getRawTopK();
        return value <= 0 ? 50 : value;
    }

    private int effectiveFinalTopK() {
        int value = properties.getAnswerEvidence().getFinalTopK();
        return value <= 0 ? 5 : value;
    }

    private Path outputDirectory() {
        String configured = System.getProperty("eval.outputDir");
        return configured == null || configured.isBlank() ? DEFAULT_OUTPUT_DIRECTORY : Path.of(configured);
    }

    private List<CodeSearchResult> safeList(List<CodeSearchResult> results) {
        return results == null ? List.of() : results;
    }

    private void printDiagnostic(CodeRagEvalCaseResult result) {
        System.out.println("=== Code RAG case diagnostic: " + result.evalCase().id + " ===");
        for (int i = 0; i < result.rawCandidates().size(); i++) {
            CodeSearchResult candidate = result.rawCandidates().get(i);
            System.out.println("raw#" + (i + 1)
                    + " chunkId=" + candidate.getChunkId()
                    + " file=" + candidate.getFilePath()
                    + " symbol=" + candidate.getSymbolName());
        }
        System.out.println("selectorSelectedChunkIds=" + result.selectorProposedChunkIds());
        System.out.println("validChunkIds=" + result.selectorValidChunkIds());
        System.out.println("invalidChunkIds=" + result.selectorInvalidChunkIds());
        System.out.println("proposedCandidateIds=" + result.selectorProposedCandidateIds());
        System.out.println("validCandidateIds=" + result.selectorValidCandidateIds());
        System.out.println("invalidCandidateIds=" + result.selectorInvalidCandidateIds());
        System.out.println("fallback=" + result.fallback());
        System.out.println("fallbackReason=" + result.fallbackReason());
        System.out.println("fallbackSelectedTop5=" + result.selectedEvidence().stream()
                .map(evidence -> evidence.getChunkId() + "|" + evidence.getFilePath() + "|" + evidence.getSymbolName())
                .toList());
        System.out.println("groundTruthInRawTop20=" + (result.groundTruthRawRank() > 0));
        System.out.println("groundTruthRawRank=" + result.groundTruthRawRank());
        System.out.println("selectorPromptChars=" + result.selectorPromptChars());
        System.out.println("selectorCandidateSectionChars=" + result.selectorCandidateSectionChars());
    }

    static boolean hasEvalRepoId() {
        String repoId = System.getProperty("eval.repoId");
        if (repoId == null || repoId.isBlank()) {
            repoId = System.getenv("CODE_RAG_EVAL_REPO_ID");
        }
        return (repoId != null && !repoId.isBlank()) || Boolean.getBoolean("eval.autoRepo");
    }
}
