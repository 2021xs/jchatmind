package com.kama.jchatmind.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.CodeRepositoryMapper;
import com.kama.jchatmind.model.dto.CodeAnswerEvidenceResult;
import com.kama.jchatmind.model.dto.CodeSearchResult;
import com.kama.jchatmind.model.entity.CodeRepository;
import com.kama.jchatmind.service.CodeRagAnswerEvidenceService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.fail;

@Tag("rag-eval")
@EnabledIf("hasEvalRepoId")
@SpringBootTest
class CodeRagFinalEvaluationTest {
    private static final String CASE_RESOURCE = "eval/code_rag_eval_cases.json";

    @Autowired
    private CodeRagAnswerEvidenceService answerEvidenceService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CodeRepositoryMapper codeRepositoryMapper;

    @Test
    void evaluateFinalAnswerEvidenceMainline() throws Exception {
        String repoId = configuredRepoId();
        List<EvalCase> cases = loadCases();
        int limit = Integer.getInteger("eval.limit", cases.size());
        List<EvalCase> selectedCases = cases.subList(0, Math.min(limit, cases.size()));

        EvaluationStats stats = new EvaluationStats();
        Map<String, CategoryStats> difficultyStats = new LinkedHashMap<>();
        Map<String, CategoryStats> categoryStats = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();

        for (EvalCase evalCase : selectedCases) {
            CategoryStats difficulty = difficultyStats.computeIfAbsent(evalCase.difficulty, key -> new CategoryStats());
            CategoryStats category = categoryStats.computeIfAbsent(evalCase.category, key -> new CategoryStats());
            CodeAnswerEvidenceResult result;
            try {
                result = retrieveWithRetry(repoId, evalCase.query);
            } catch (RuntimeException e) {
                stats.total++;
                stats.retrievalErrorCount++;
                difficulty.total++;
                category.total++;
                failures.add(errorBlock(evalCase, e));
                continue;
            }
            List<CodeSearchResult> evidence = safeList(result.getSelectedEvidence());

            boolean hitAt1 = hitWithin(evidence, evalCase, 1);
            boolean hitAt3 = hitWithin(evidence, evalCase, 3);
            boolean hitAt5 = hitWithin(evidence, evalCase, 5);

            stats.total++;
            stats.selectedAt1 += hitAt1 ? 1 : 0;
            stats.selectedAt3 += hitAt3 ? 1 : 0;
            stats.selectedAt5 += hitAt5 ? 1 : 0;
            stats.fallbackCount += result.isFallback() ? 1 : 0;
            stats.jsonParseOkCount += result.isJsonParseOk() ? 1 : 0;

            difficulty.total++;
            difficulty.selectedAt1 += hitAt1 ? 1 : 0;
            difficulty.selectedAt5 += hitAt5 ? 1 : 0;

            category.total++;
            category.selectedAt1 += hitAt1 ? 1 : 0;
            category.selectedAt5 += hitAt5 ? 1 : 0;

            if (!hitAt5) {
                failures.add(failureBlock(evalCase, evidence));
            }
        }

        String report = renderReport(stats, difficultyStats, categoryStats, failures);
        writeReport(report);
        System.out.println(report);

        if (Boolean.getBoolean("eval.failOnMiss") && !failures.isEmpty()) {
            fail("Code RAG final evaluation selected@5 failures: " + failures.size());
        }
    }

    private CodeAnswerEvidenceResult retrieveWithRetry(String repoId, String query) {
        int retries = Integer.getInteger("eval.retryCount", 1);
        RuntimeException last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                return answerEvidenceService.retrieve(repoId, query);
            } catch (RuntimeException e) {
                last = e;
                if (attempt < retries) {
                    sleepBeforeRetry(attempt);
                }
            }
        }
        throw last;
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(1000L * (attempt + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<EvalCase> loadCases() throws IOException {
        ClassPathResource resource = new ClassPathResource(CASE_RESOURCE);
        return objectMapper.readValue(resource.getInputStream(), new TypeReference<>() {
        });
    }

    private boolean hitWithin(List<CodeSearchResult> evidence, EvalCase evalCase, int topK) {
        int limit = Math.min(topK, evidence.size());
        for (int i = 0; i < limit; i++) {
            if (isHit(evidence.get(i), evalCase)) {
                return true;
            }
        }
        return false;
    }

    private boolean isHit(CodeSearchResult result, EvalCase evalCase) {
        boolean chunkTypeOk = isEmpty(evalCase.expectedChunkTypes)
                || containsAny(List.of(safe(result.getChunkType())), evalCase.expectedChunkTypes);
        if (!chunkTypeOk) {
            return false;
        }

        boolean fileHit = containsAny(List.of(safe(result.getFilePath())), evalCase.expectedFileKeywords);
        boolean symbolHit = containsAny(List.of(
                safe(result.getSymbolName()),
                safe(result.getApiPath()),
                safe(result.getContentPreview()),
                safe(result.getMetadata())
        ), evalCase.expectedSymbolKeywords);
        return fileHit || symbolHit;
    }

    private boolean containsAny(List<String> haystacks, List<String> needles) {
        if (isEmpty(needles)) {
            return false;
        }
        for (String haystack : haystacks) {
            String normalizedHaystack = safe(haystack).toLowerCase(Locale.ROOT);
            for (String needle : needles) {
                if (!safe(needle).isBlank()
                        && normalizedHaystack.contains(needle.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private String renderReport(EvaluationStats stats,
                                Map<String, CategoryStats> difficultyStats,
                                Map<String, CategoryStats> categoryStats,
                                List<String> failures) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Code RAG Final Evaluation Report\n\n");
        builder.append("## Scope\n\n");
        builder.append("This test calls CodeRagAnswerEvidenceService.retrieve and evaluates selected evidence only.\n");
        builder.append("It does not test pgvector raw Top-K directly and does not measure final natural-language answer accuracy.\n\n");
        builder.append("## Summary\n\n");
        builder.append("- total cases: ").append(stats.total).append('\n');
        builder.append("- selected@1: ").append(stats.selectedAt1).append('/').append(stats.total).append('\n');
        builder.append("- selected@3: ").append(stats.selectedAt3).append('/').append(stats.total).append('\n');
        builder.append("- selected@5: ").append(stats.selectedAt5).append('/').append(stats.total).append('\n');
        builder.append("- fallback count: ").append(stats.fallbackCount).append('\n');
        builder.append("- jsonParseOk count: ").append(stats.jsonParseOkCount).append('\n');
        builder.append("- retrieval error count: ").append(stats.retrievalErrorCount).append('\n');
        builder.append('\n');
        builder.append("## Difficulty Breakdown\n\n");
        builder.append("| Difficulty | Cases | selected@1 | selected@5 |\n");
        builder.append("| --- | ---: | ---: | ---: |\n");
        for (Map.Entry<String, CategoryStats> entry : difficultyStats.entrySet()) {
            CategoryStats value = entry.getValue();
            builder.append("| ").append(entry.getKey())
                    .append(" | ").append(value.total)
                    .append(" | ").append(value.selectedAt1).append('/').append(value.total)
                    .append(" | ").append(value.selectedAt5).append('/').append(value.total)
                    .append(" |\n");
        }
        builder.append('\n');
        builder.append("## Category Breakdown\n\n");
        builder.append("| Category | Cases | selected@1 | selected@5 |\n");
        builder.append("| --- | ---: | ---: | ---: |\n");
        for (Map.Entry<String, CategoryStats> entry : categoryStats.entrySet()) {
            CategoryStats value = entry.getValue();
            builder.append("| ").append(entry.getKey())
                    .append(" | ").append(value.total)
                    .append(" | ").append(value.selectedAt1).append('/').append(value.total)
                    .append(" | ").append(value.selectedAt5).append('/').append(value.total)
                    .append(" |\n");
        }
        builder.append('\n');
        builder.append("## Hit Rule\n\n");
        builder.append("A selected evidence item is counted as hit when its chunkType matches expectedChunkTypes if present, ")
                .append("and its filePath or symbolName/apiPath/contentPreview/metadata contains an expected keyword.\n\n");
        builder.append("## Failed Cases\n\n");
        if (failures.isEmpty()) {
            builder.append("None.\n");
        } else {
            for (String failure : failures) {
                builder.append(failure).append('\n');
            }
        }
        return builder.toString();
    }

    private String errorBlock(EvalCase evalCase, RuntimeException e) {
        StringBuilder builder = new StringBuilder();
        builder.append("### ").append(evalCase.id).append('\n');
        builder.append("- difficulty: ").append(evalCase.difficulty).append('\n');
        builder.append("- category: ").append(evalCase.category).append('\n');
        builder.append("- query: ").append(evalCase.query).append('\n');
        builder.append("- expectedFileKeywords: ").append(evalCase.expectedFileKeywords).append('\n');
        builder.append("- expectedSymbolKeywords: ").append(evalCase.expectedSymbolKeywords).append('\n');
        builder.append("- expectedChunkTypes: ").append(evalCase.expectedChunkTypes).append('\n');
        builder.append("- retrievalError: ").append(e.getClass().getSimpleName()).append(": ")
                .append(truncate(e.getMessage(), 220)).append('\n');
        return builder.toString();
    }

    private String failureBlock(EvalCase evalCase, List<CodeSearchResult> evidence) {
        StringBuilder builder = new StringBuilder();
        builder.append("### ").append(evalCase.id).append('\n');
        builder.append("- difficulty: ").append(evalCase.difficulty).append('\n');
        builder.append("- category: ").append(evalCase.category).append('\n');
        builder.append("- query: ").append(evalCase.query).append('\n');
        builder.append("- expectedFileKeywords: ").append(evalCase.expectedFileKeywords).append('\n');
        builder.append("- expectedSymbolKeywords: ").append(evalCase.expectedSymbolKeywords).append('\n');
        builder.append("- expectedChunkTypes: ").append(evalCase.expectedChunkTypes).append('\n');
        builder.append("- actual selected evidence:\n");
        for (int i = 0; i < evidence.size(); i++) {
            CodeSearchResult result = evidence.get(i);
            builder.append("  - #").append(i + 1)
                    .append(" filePath=").append(result.getFilePath())
                    .append(", chunkType=").append(result.getChunkType())
                    .append(", symbolName=").append(result.getSymbolName())
                    .append(", apiPath=").append(result.getApiPath())
                    .append(", score=").append(result.getScore())
                    .append(", source=").append(result.getRerankSource())
                    .append(", preview=").append(truncate(result.getContentPreview(), 120))
                    .append('\n');
        }
        return builder.toString();
    }

    private void writeReport(String report) throws IOException {
        Path reportPath = Path.of("target", "eval", "code-rag-final-evaluation-report.md");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report, StandardCharsets.UTF_8);
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
        return repoId;
    }

    static boolean hasEvalRepoId() {
        String repoId = System.getProperty("eval.repoId");
        if (repoId == null || repoId.isBlank()) {
            repoId = System.getenv("CODE_RAG_EVAL_REPO_ID");
        }
        return (repoId != null && !repoId.isBlank()) || Boolean.getBoolean("eval.autoRepo");
    }

    private List<CodeSearchResult> safeList(List<CodeSearchResult> results) {
        return results == null ? List.of() : results;
    }

    private boolean isEmpty(List<String> values) {
        return values == null || values.isEmpty();
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "...";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class EvalCase {
        public String id;
        public String query;
        public List<String> expectedFileKeywords = List.of();
        public List<String> expectedSymbolKeywords = List.of();
        public List<String> expectedChunkTypes = List.of();
        public String category;
        public String difficulty;
    }

    static class EvaluationStats {
        int total;
        int selectedAt1;
        int selectedAt3;
        int selectedAt5;
        int fallbackCount;
        int jsonParseOkCount;
        int retrievalErrorCount;
    }

    static class CategoryStats {
        int total;
        int selectedAt1;
        int selectedAt5;
    }
}
