package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.model.dto.CodeEvidenceCandidateCard;
import com.kama.jchatmind.model.dto.SelectorModelResponse;
import com.kama.jchatmind.service.LlmSelectorClient;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeLlmEvidenceSelectorTest {

    @Test
    void emptySelectorIdsRecordQualityFallbackDiagnostics() throws Exception {
        CodeLlmEvidenceSelector selector = selectorReturning("{\"selectedCandidateIds\":[]}");

        var result = selector.select("query", List.of(candidate("raw-1"), candidate("raw-2")));

        assertTrue(result.isFallback());
        assertTrue(result.isJsonParseOk());
        assertTrue(result.isEmptySelectorResult());
        assertFalse(result.isExecutionError());
        assertEquals(List.of(), result.getProposedChunkIds());
        assertEquals(List.of(), result.getValidChunkIds());
        assertEquals(List.of(), result.getInvalidChunkIds());
        assertEquals(List.of(), result.getProposedCandidateIds());
        assertEquals(List.of("raw-1", "raw-2"), result.getSelectedChunkIds());
    }

    @Test
    void invalidSelectorIdsAreRecordedBeforeFallback() throws Exception {
        CodeLlmEvidenceSelector selector = selectorReturning(
                "{\"selectedCandidateIds\":[\"C99\"]}");

        var result = selector.select("query", List.of(candidate("raw-1")));

        assertTrue(result.isFallback());
        assertFalse(result.isEmptySelectorResult());
        assertEquals(List.of(), result.getProposedChunkIds());
        assertEquals(List.of(), result.getValidChunkIds());
        assertEquals(List.of(), result.getInvalidChunkIds());
        assertEquals(List.of("C99"), result.getProposedCandidateIds());
        assertEquals(List.of("C99"), result.getInvalidCandidateIds());
        assertTrue(result.getReason().startsWith("INVALID_CANDIDATE_ID:"));
    }

    @Test
    void timeoutCancelsSharedExecutorTaskAndFallsBackToCandidateOrder() throws Exception {
        LlmSelectorClient client = mock(LlmSelectorClient.class);
        AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
        Future<SelectorModelResponse> future = mock(Future.class);
        CodeRagProperties properties = new CodeRagProperties();
        properties.getLlmSelector().setTimeoutMs(10);

        when(executor.submit(any(Callable.class))).thenReturn(future);
        when(future.get(anyLong(), eq(TimeUnit.MILLISECONDS))).thenThrow(new TimeoutException("timed out"));

        CodeLlmEvidenceSelector selector =
                new CodeLlmEvidenceSelector(client, properties, new ObjectMapper(), executor);
        var result = selector.select("query", List.of(candidate("raw-1"), candidate("raw-2")));

        assertTrue(result.isFallback());
        assertFalse(result.isJsonParseOk());
        assertTrue(result.isExecutionError());
        assertEquals("SELECTOR_TIMEOUT: selector execution failed: selector timed out after 10 ms",
                result.getReason());
        assertEquals(List.of("raw-1", "raw-2"), result.getSelectedChunkIds());
        verify(future).cancel(true);
    }

    @Test
    void recordsProviderUsageWithoutChangingSelection() throws Exception {
        CodeLlmEvidenceSelector selector = selectorReturning(
                "{\"selectedCandidateIds\":[\"C01\"]}", 120, 8, 128);

        var result = selector.select("query", List.of(candidate("raw-1")));

        assertFalse(result.isFallback());
        assertTrue(result.isUsageAvailable());
        assertEquals(120, result.getPromptTokens());
        assertEquals(8, result.getCompletionTokens());
        assertEquals(128, result.getTotalTokens());
        assertEquals(List.of("raw-1"), result.getSelectedChunkIds());
    }

    @Test
    void missingProviderUsageDoesNotChangeSelection() throws Exception {
        CodeLlmEvidenceSelector selector = selectorReturning("{\"selectedCandidateIds\":[\"C01\"]}");

        var result = selector.select("query", List.of(candidate("raw-1")));

        assertFalse(result.isFallback());
        assertFalse(result.isUsageAvailable());
        assertEquals(null, result.getPromptTokens());
        assertEquals(List.of("raw-1"), result.getSelectedChunkIds());
    }

    @Test
    void localIdsMapFirstAndTwentiethCandidatesBackToRealChunkIds() throws Exception {
        List<CodeEvidenceCandidateCard> candidates = java.util.stream.IntStream.rangeClosed(1, 20)
                .mapToObj(index -> candidate("uuid-" + index)).toList();
        CodeLlmEvidenceSelector selector = selectorReturning(
                "{\"selectedCandidateIds\":[\"C20\",\"C01\"]}");

        var result = selector.select("query", candidates);

        assertEquals(List.of("C20", "C01"), result.getValidCandidateIds());
        assertEquals(List.of("uuid-20", "uuid-1"), result.getSelectedChunkIds());
    }

    @Test
    void mixedIdsAreDeduplicatedOrderedValidatedAndLimited() throws Exception {
        List<CodeEvidenceCandidateCard> candidates = java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToObj(index -> candidate("uuid-" + index)).toList();
        CodeLlmEvidenceSelector selector = selectorReturning(
                "{\"selectedCandidateIds\":[\"C03\",\"C01\",\"C03\",\"C99\",\"c02\",\"C02\",\"C04\",\"C05\",\"C06\"]}");

        var result = selector.select("query", candidates);

        assertFalse(result.isFallback());
        assertEquals(List.of("C03", "C01", "C99", "c02", "C02", "C04", "C05", "C06"),
                result.getProposedCandidateIds());
        assertEquals(List.of("C03", "C01", "C02", "C04", "C05"), result.getValidCandidateIds());
        assertEquals(List.of("C99", "c02"), result.getInvalidCandidateIds());
        assertEquals(List.of("uuid-3", "uuid-1", "uuid-2", "uuid-4", "uuid-5"),
                result.getSelectedChunkIds());
    }

    @Test
    void missingOrNonArrayIdsFallbackToRawTopFive() throws Exception {
        List<CodeEvidenceCandidateCard> candidates = java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToObj(index -> candidate("uuid-" + index)).toList();

        var missing = selectorReturning("{}").select("query", candidates);
        var nonArray = selectorReturning("{\"selectedCandidateIds\":\"C01\"}").select("query", candidates);

        assertTrue(missing.isFallback());
        assertTrue(missing.isEmptySelectorResult());
        assertTrue(missing.getReason().startsWith("EMPTY_SELECTION:"));
        assertEquals(List.of("uuid-1", "uuid-2", "uuid-3", "uuid-4", "uuid-5"),
                missing.getSelectedChunkIds());
        assertTrue(nonArray.isFallback());
        assertTrue(nonArray.isEmptySelectorResult());
    }

    @Test
    void nonJsonFallsBackButFencedJsonParses() throws Exception {
        var parseFailure = selectorReturning("not-json").select("query", List.of(candidate("uuid-1")));
        var fenced = selectorReturning("```json\n{\"selectedCandidateIds\":[\"C01\"]}\n```")
                .select("query", List.of(candidate("uuid-1")));

        assertTrue(parseFailure.isFallback());
        assertFalse(parseFailure.isJsonParseOk());
        assertFalse(parseFailure.isExecutionError());
        assertTrue(parseFailure.getReason().startsWith("JSON_PARSE_ERROR:"));
        assertFalse(fenced.isFallback());
        assertEquals(List.of("uuid-1"), fenced.getSelectedChunkIds());
    }

    @Test
    void promptUsesLocalIdAndV2bCompressedCandidateFields() throws Exception {
        CodeLlmEvidenceSelector selector = selectorReturning("{}");
        CodeEvidenceCandidateCard card = CodeEvidenceCandidateCard.builder()
                .chunkId("00000000-0000-0000-0000-000000000001")
                .chunkType("CONTROLLER_API").filePath("Controller.java").symbolName("Controller#method")
                .apiPath("/demo").httpMethod("GET").startLine(10).endLine(20)
                .metadataSummary("{\"annotations\":[\"GetMapping\"]}")
                .evidenceRole("API_ENTRY").evidenceHint("hint").rawRank(1).candidateRank(1)
                .candidateScore(0.9).snippet("snippet").build();

        String prompt = selector.buildPromptForCandidates("query", List.of(card));

        assertTrue(prompt.contains("[C01]"));
        assertFalse(prompt.contains(card.getChunkId()));
        assertTrue(prompt.contains("type: CONTROLLER_API"));
        assertTrue(prompt.contains("file: Controller.java"));
        assertTrue(prompt.contains("symbol: Controller#method"));
        assertTrue(prompt.contains("lines: 10-20"));
        assertTrue(prompt.contains("api: GET /demo"));
        assertTrue(prompt.contains("signals: annotations=GetMapping"));
        assertTrue(prompt.contains("snippet:\nsnippet"));
        assertFalse(prompt.contains("evidenceRole:"));
        assertFalse(prompt.contains("evidenceHint:"));
        assertFalse(prompt.contains("rawRank:"));
        assertFalse(prompt.contains("candidateScore:"));
        assertTrue(prompt.contains("{\"selectedCandidateIds\""));
        assertFalse(prompt.contains("answerType"));
        assertFalse(prompt.contains("\"reason\""));
    }

    @Test
    void recordsPromptAndCandidateSectionCharsWithoutChangingSelection() throws Exception {
        CodeLlmEvidenceSelector selector = selectorReturning("{\"selectedCandidateIds\":[\"C01\"]}");

        var result = selector.select("query", List.of(candidate("uuid-1")));

        assertEquals(List.of("uuid-1"), result.getSelectedChunkIds());
        assertTrue(result.getPromptChars() > result.getCandidateSectionChars());
        assertTrue(result.getCandidateSectionChars() > 0);
        assertEquals("{\"selectedCandidateIds\":[\"C01\"]}".length(), result.getResponseChars());
    }

    @Test
    void promptDoesNotAssumeATwentyCandidateMaximum() throws Exception {
        CodeLlmEvidenceSelector selector = selectorReturning("{}");

        for (int count : List.of(6, 20, 25)) {
            List<CodeEvidenceCandidateCard> candidates = java.util.stream.IntStream.rangeClosed(1, count)
                    .mapToObj(index -> candidate("uuid-" + index)).toList();

            String prompt = selector.buildPromptForCandidates("query", candidates);

            assertTrue(prompt.contains("Candidate cards use local ids such as C01, C02, and so on."));
            assertFalse(prompt.contains("C01 through C20"));
            assertFalse(prompt.contains("C01-C20"));
            if (count > 20) {
                assertTrue(prompt.contains("[C25]"));
            }
        }
    }

    private CodeLlmEvidenceSelector selectorReturning(String response) throws Exception {
        LlmSelectorClient client = mock(LlmSelectorClient.class);
        AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
        CodeRagProperties properties = new CodeRagProperties();
        when(client.call(anyString())).thenReturn(
                new SelectorModelResponse(response, null, null, null, null, null, null));
        completeSubmittedTasks(executor);
        return new CodeLlmEvidenceSelector(client, properties, new ObjectMapper(), executor);
    }

    private CodeLlmEvidenceSelector selectorReturning(String response, int promptTokens,
                                                       int completionTokens, int totalTokens) throws Exception {
        LlmSelectorClient client = mock(LlmSelectorClient.class);
        AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
        CodeRagProperties properties = new CodeRagProperties();
        when(client.call(anyString())).thenReturn(new SelectorModelResponse(
                response, null, null, promptTokens, completionTokens, totalTokens, null));
        completeSubmittedTasks(executor);
        return new CodeLlmEvidenceSelector(client, properties, new ObjectMapper(), executor);
    }

    @SuppressWarnings("unchecked")
    private void completeSubmittedTasks(AsyncTaskExecutor executor) {
        when(executor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<SelectorModelResponse> task = invocation.getArgument(0);
            return CompletableFuture.completedFuture(task.call());
        });
    }

    private CodeEvidenceCandidateCard candidate(String chunkId) {
        return CodeEvidenceCandidateCard.builder()
                .chunkId(chunkId)
                .chunkType("SERVICE_METHOD")
                .filePath("Service.java")
                .snippet("snippet")
                .build();
    }
}
