package com.kama.jchatmind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.tools.CodeSearchEvidenceFormatter;
import com.kama.jchatmind.config.ContextCompressionProperties;
import com.kama.jchatmind.config.ToolDuplicateDetectionProperties;
import com.kama.jchatmind.config.ToolResultProperties;
import com.kama.jchatmind.config.ToolTimeoutProperties;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.dto.CodeSearchResult;
import com.kama.jchatmind.service.ToolExecutionService;
import com.kama.jchatmind.service.impl.EstimatedTokenCounter;
import com.kama.jchatmind.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.core.task.AsyncTaskExecutor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StructuredCodeSearchProjectionTest {

    private ContextCompressionProperties compressionProperties;
    private ToolCallBatchExecutor batchExecutor;

    @BeforeEach
    void setUp() {
        compressionProperties = new ContextCompressionProperties();
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.canonicalName(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ToolResultProperties resultProperties = new ToolResultProperties();
        resultProperties.setDefaultMaxResultChars(8_000);
        batchExecutor = new ToolCallBatchExecutor(
                mock(ToolExecutionService.class), mock(AsyncTaskExecutor.class),
                new ToolTimeoutProperties(), new ToolResultGuard(resultProperties),
                new ToolDuplicateCallDetector(new ObjectMapper(), new ToolDuplicateDetectionProperties()),
                toolRegistry, new EstimatedTokenCounter(compressionProperties), compressionProperties);
    }

    @Test
    void smallStructuredSearchBatchKeepsExactExistingModelView() {
        compressionProperties.setMaxSingleToolResultTokens(2_000);
        String canonical = searchCanonical("small", 2, 24);

        String projected = project(List.of(searchResponse("search-small", canonical)))
                .get(0).responseData();

        assertEquals(canonical, projected);
        assertFalse(projected.contains("MODEL_VIEW_BOUNDED"));
    }

    @Test
    void oversizedStructuredSearchPreservesAllEvidenceSemanticTailAndExactLocators() {
        compressionProperties.setMaxSingleToolResultTokens(450);
        String canonical = searchCanonical("tail", 5, 1_200);
        ToolResponseMessage.ToolResponse persistent = searchResponse("search-tail", canonical);

        ToolResponseMessage.ToolResponse projected = project(List.of(persistent)).get(0);

        assertEquals("search-tail", projected.id());
        assertEquals("searchProjectCode", projected.name());
        assertTrue(projected.responseData().contains("MODEL_VIEW_BOUNDED"));
        assertTrue(projected.responseData().contains("SNIPPET_BOUNDED")
                || projected.responseData().contains("SNIPPET_OMITTED_FROM_MODEL_VIEW"));
        for (int index = 1; index <= 5; index++) {
            assertTrue(projected.responseData().contains("repoId: repo-tail-" + index));
            assertTrue(projected.responseData().contains("chunkId: chunk-tail-" + index));
            assertTrue(projected.responseData().contains("Symbol" + index + "#method"));
        }
        assertTrue(projected.responseData().contains("TAIL_SEMANTIC_C5"));
        assertTrue(projected.responseData().length() < canonical.length());
        assertEquals(canonical, persistent.responseData());
    }

    @Test
    void threeSearchCallsKeepFifteenHeadersSkeletonsIdsAndOrder() {
        compressionProperties.setMaxSingleToolResultTokens(1_800);
        List<ToolResponseMessage.ToolResponse> persistent = List.of(
                searchResponse("call-a", searchCanonical("A", 5, 900)),
                searchResponse("call-b", searchCanonical("B", 5, 900)),
                searchResponse("call-c", searchCanonical("C", 5, 900)));

        List<ToolResponseMessage.ToolResponse> projected = project(persistent);

        assertEquals(List.of("call-a", "call-b", "call-c"),
                projected.stream().map(ToolResponseMessage.ToolResponse::id).toList());
        assertEquals(3, projected.size());
        for (int response = 0; response < 3; response++) {
            String prefix = List.of("A", "B", "C").get(response);
            for (int evidence = 1; evidence <= 5; evidence++) {
                String body = projected.get(response).responseData();
                assertTrue(body.contains("repoId: repo-" + prefix + "-" + evidence));
                assertTrue(body.contains("chunkId: chunk-" + prefix + "-" + evidence));
                assertTrue(body.contains("METHOD_SURFACE"));
            }
        }
        assertTrue(totalChars(projected) < totalChars(persistent));
        assertTrue(batchEstimatedTokens(projected) < compressionProperties.getMaxSingleToolResultTokens());
    }

    @Test
    void sevenCallBatchKeepsThirtyFiveEvidenceWhileControllingDetail() {
        compressionProperties.setMaxSingleToolResultTokens(2_600);
        List<ToolResponseMessage.ToolResponse> persistent = IntStream.rangeClosed(1, 7)
                .mapToObj(index -> searchResponse(
                        "call-" + index, searchCanonical("S" + index, 5, 1_000)))
                .toList();

        List<ToolResponseMessage.ToolResponse> projected = project(persistent);

        assertEquals(7, projected.size());
        for (int response = 1; response <= 7; response++) {
            assertEquals("call-" + response, projected.get(response - 1).id());
            for (int evidence = 1; evidence <= 5; evidence++) {
                assertTrue(projected.get(response - 1).responseData()
                        .contains("chunkId: chunk-S" + response + "-" + evidence));
            }
        }
        assertTrue(totalChars(projected) < totalChars(persistent) / 2);
    }

    @Test
    void mixedBatchProjectsOnlySearchAndKeepsExactChunkView() {
        compressionProperties.setMaxSingleToolResultTokens(900);
        String exact = "repoId: repo-exact\nchunkId: chunk-exact\ncontent:\n" + "E".repeat(900);
        List<ToolResponseMessage.ToolResponse> persistent = List.of(
                searchResponse("search-a", searchCanonical("mixA", 5, 700)),
                new ToolResponseMessage.ToolResponse("exact-b", "getCodeChunk", exact),
                searchResponse("search-c", searchCanonical("mixC", 5, 700)));

        List<ToolResponseMessage.ToolResponse> projected = project(persistent);

        assertEquals(List.of("search-a", "exact-b", "search-c"),
                projected.stream().map(ToolResponseMessage.ToolResponse::id).toList());
        assertEquals(exact, projected.get(1).responseData());
        assertTrue(projected.get(0).responseData().contains("MODEL_VIEW_BOUNDED"));
        assertTrue(projected.get(2).responseData().contains("MODEL_VIEW_BOUNDED"));
    }

    @Test
    void mandatorySemanticFloorWinsOverImpossibleAggregateBudget() {
        compressionProperties.setMaxSingleToolResultTokens(10);
        String canonical = searchCanonical("floor", 5, 500);

        String projected = project(List.of(searchResponse("floor-call", canonical)))
                .get(0).responseData();

        for (int index = 1; index <= 5; index++) {
            assertTrue(projected.contains("repoId: repo-floor-" + index));
            assertTrue(projected.contains("chunkId: chunk-floor-" + index));
            assertTrue(projected.contains("METHOD_SURFACE"));
        }
        assertTrue(projected.contains("SNIPPET_OMITTED_FROM_MODEL_VIEW"));
        assertTrue(projected.length() < canonical.length());
    }

    @Test
    void liveAndReloadUseIdenticalProjectionWithoutChangingCanonicalBodies() throws Exception {
        compressionProperties.setMaxSingleToolResultTokens(900);
        List<ToolResponseMessage.ToolResponse> persistent = List.of(
                searchResponse("live-a", new ObjectMapper().writeValueAsString(
                        searchCanonical("liveA", 5, 800))),
                searchResponse("live-b", new ObjectMapper().writeValueAsString(
                        searchCanonical("liveB", 5, 800))),
                searchResponse("live-c", new ObjectMapper().writeValueAsString(
                        searchCanonical("liveC", 5, 800))));
        List<String> canonicalBodies = persistent.stream()
                .map(ToolResponseMessage.ToolResponse::responseData).toList();

        ToolCallBatchResult batch = successfulBatch(persistent);
        List<ToolResponseMessage.ToolResponse> live = batchExecutor.projectForContext(
                null, batch, batch.getToolResponseMessage()).toolResponseMessage().getResponses();
        List<ToolResponseMessage.ToolResponse> reloaded = project(persistent);

        assertEquals(canonicalBodies, persistent.stream()
                .map(ToolResponseMessage.ToolResponse::responseData).toList());
        assertEquals(live.stream().map(ToolResponseMessage.ToolResponse::responseData).toList(),
                reloaded.stream().map(ToolResponseMessage.ToolResponse::responseData).toList());
        assertNotEquals(canonicalBodies,
                live.stream().map(ToolResponseMessage.ToolResponse::responseData).toList());
        assertTrue(persistent.get(2).responseData().contains("TAIL_SEMANTIC_C5"));
    }

    @Test
    void persistedProtocolReloadPreservesAssistantResponseIdentityAndOrder() {
        compressionProperties.setMaxSingleToolResultTokens(700);
        List<AssistantMessage.ToolCall> calls = List.of(
                call("protocol-a", "searchProjectCode"),
                call("protocol-b", "getCodeChunk"),
                call("protocol-c", "searchProjectCode"));
        String exact = "repoId: repo-B\nchunkId: B\ncontent:\n" + "exact".repeat(100);
        List<ToolResponseMessage.ToolResponse> responses = List.of(
                searchResponse("protocol-a", searchCanonical("protocolA", 5, 700)),
                new ToolResponseMessage.ToolResponse("protocol-b", "getCodeChunk", exact),
                searchResponse("protocol-c", searchCanonical("protocolC", 5, 700)));
        ChatMessageDTO assistant = ChatMessageDTO.builder()
                .id("assistant-1").sessionId("session-1").role(ChatMessageDTO.RoleType.ASSISTANT)
                .content("").metadata(ChatMessageDTO.MetaData.builder()
                        .taskId("task-1").toolCalls(calls).build()).build();
        List<ChatMessageDTO> protocol = new ArrayList<>();
        protocol.add(assistant);
        for (int index = 0; index < responses.size(); index++) {
            ToolResponseMessage.ToolResponse response = responses.get(index);
            protocol.add(ChatMessageDTO.builder()
                    .id("tool-message-" + index).sessionId("session-1")
                    .role(ChatMessageDTO.RoleType.TOOL).content(response.responseData())
                    .metadata(ChatMessageDTO.MetaData.builder()
                            .taskId("task-1").toolResponse(response).build()).build());
        }

        List<ChatMessageDTO> projected =
                batchExecutor.projectPersistedProtocolForContext(null, protocol);

        assertEquals(4, projected.size());
        assertEquals("assistant-1", projected.get(0).getId());
        assertEquals(calls, projected.get(0).getMetadata().getToolCalls());
        assertEquals(List.of("protocol-a", "protocol-b", "protocol-c"),
                projected.subList(1, 4).stream()
                        .map(message -> message.getMetadata().getToolResponse().id()).toList());
        assertEquals(exact, projected.get(2).getContent());
    }

    @Test
    void projectionPreservesSuccessErrorSkippedStatusAndResponseOrder() {
        compressionProperties.setMaxSingleToolResultTokens(450);
        List<ToolResponseMessage.ToolResponse> persistent = List.of(
                searchResponse("terminal-a", searchCanonical("terminal", 5, 900)),
                new ToolResponseMessage.ToolResponse("terminal-b", "toolB", "ERROR: terminal failure"),
                new ToolResponseMessage.ToolResponse("terminal-c", "toolC", "SKIPPED: batch aborted"));
        ToolCallBatchResult batch = terminalBatch(persistent);

        List<ToolResponseMessage.ToolResponse> projected = batchExecutor.projectForContext(
                null, batch, batch.getToolResponseMessage()).toolResponseMessage().getResponses();

        assertEquals(List.of("terminal-a", "terminal-b", "terminal-c"),
                projected.stream().map(ToolResponseMessage.ToolResponse::id).toList());
        assertEquals(ToolCallBatchResult.TerminalStatus.SUCCESS,
                batch.getTerminalStatuses().get("terminal-a"));
        assertEquals(ToolCallBatchResult.TerminalStatus.ERROR,
                batch.getTerminalStatuses().get("terminal-b"));
        assertEquals(ToolCallBatchResult.TerminalStatus.SKIPPED,
                batch.getTerminalStatuses().get("terminal-c"));
        assertTrue(projected.get(0).responseData().contains("MODEL_VIEW_BOUNDED"));
    }

    @Test
    void deterministicSizeSimulationRetainsEveryLocator() {
        compressionProperties.setMaxSingleToolResultTokens(2_600);
        for (int callCount : List.of(1, 3, 5, 7)) {
            List<ToolResponseMessage.ToolResponse> persistent = IntStream.rangeClosed(1, callCount)
                    .mapToObj(index -> searchResponse(
                            "sim-" + index, searchCanonical("SIM" + index, 5, 1_000)))
                    .toList();
            List<ToolResponseMessage.ToolResponse> projected = project(persistent);
            long locatorCount = projected.stream()
                    .map(ToolResponseMessage.ToolResponse::responseData)
                    .mapToLong(body -> occurrences(body, "chunkId: chunk-SIM"))
                    .sum();
            System.out.printf("SEMANTIC_SKELETON_SIZE calls=%d canonicalChars=%d projectedChars=%d "
                            + "evidence=%d locators=%d%n",
                    callCount, totalChars(persistent), totalChars(projected), callCount * 5, locatorCount);
            assertEquals(callCount * 5L, locatorCount);
            assertEquals(callCount, projected.size());
            if (callCount == 1) {
                assertEquals(totalChars(persistent), totalChars(projected));
            } else {
                assertTrue(totalChars(projected) < totalChars(persistent));
            }
        }
    }

    @Test
    void projectionPathHasNoLlmOrSummaryClientDependency() {
        List<String> fieldTypes = java.util.Arrays.stream(ToolCallBatchExecutor.class.getDeclaredFields())
                .map(field -> field.getType().getSimpleName()).toList();

        assertFalse(fieldTypes.stream().anyMatch(name -> name.contains("ChatClient")
                || name.contains("SummaryClient") || name.contains("LanguageModel")));
    }

    private List<ToolResponseMessage.ToolResponse> project(
            List<ToolResponseMessage.ToolResponse> persistent) {
        return batchExecutor.projectPersistedResponsesForContext(null, persistent);
    }

    private ToolCallBatchResult successfulBatch(List<ToolResponseMessage.ToolResponse> responses) {
        Map<String, ToolCallBatchResult.TerminalStatus> statuses = new LinkedHashMap<>();
        responses.forEach(response -> statuses.put(
                response.id(), ToolCallBatchResult.TerminalStatus.SUCCESS));
        return batch(responses, statuses);
    }

    private ToolCallBatchResult terminalBatch(List<ToolResponseMessage.ToolResponse> responses) {
        return batch(responses, Map.of(
                "terminal-a", ToolCallBatchResult.TerminalStatus.SUCCESS,
                "terminal-b", ToolCallBatchResult.TerminalStatus.ERROR,
                "terminal-c", ToolCallBatchResult.TerminalStatus.SKIPPED));
    }

    private ToolCallBatchResult batch(
            List<ToolResponseMessage.ToolResponse> responses,
            Map<String, ToolCallBatchResult.TerminalStatus> statuses) {
        ToolResponseMessage responseMessage = ToolResponseMessage.builder().responses(responses).build();
        AssistantMessage assistant = AssistantMessage.builder().content("")
                .toolCalls(responses.stream().map(response -> call(response.id(), response.name())).toList())
                .build();
        return ToolCallBatchResult.builder()
                .status(ToolCallBatchResult.Status.SUCCESS)
                .records(List.of())
                .toolResponseMessage(responseMessage)
                .terminalStatuses(statuses)
                .toolExecutionResult(ToolExecutionResult.builder()
                        .conversationHistory(List.of(assistant, responseMessage))
                        .returnDirect(false).build())
                .build();
    }

    private ToolResponseMessage.ToolResponse searchResponse(String callId, String canonical) {
        return new ToolResponseMessage.ToolResponse(callId, "searchProjectCode", canonical);
    }

    private AssistantMessage.ToolCall call(String id, String name) {
        return new AssistantMessage.ToolCall(id, "function", name, "{}");
    }

    private String searchCanonical(String prefix, int evidenceCount, int snippetChars) {
        List<CodeSearchResult> evidence = IntStream.rangeClosed(1, evidenceCount)
                .mapToObj(index -> CodeSearchResult.builder()
                        .repoId("repo-" + prefix + "-" + index)
                        .chunkId("chunk-" + prefix + "-" + index)
                        .filePath("File-" + prefix + "-" + index + ".java")
                        .symbolName("Symbol" + index + "#method")
                        .chunkType("METHOD").startLine(index * 10).endLine(index * 10 + 9)
                        .contentPreview(methodPreview(prefix, index, snippetChars)).build())
                .toList();
        return "Code evidence novelty:\nreturnedEvidenceCount=" + evidenceCount
                + "\nnewEvidenceCount=" + evidenceCount + "\nduplicateEvidenceCount=0\n"
                + "newFiles=[]\nnewSymbols=[]\n\n"
                + new CodeSearchEvidenceFormatter().format(evidence);
    }

    private String methodPreview(String prefix, int index, int snippetChars) {
        String first = index == 5 ? "TAIL_SEMANTIC_C5();" : "firstSemantic" + index + "();";
        String filler = ("detail-" + prefix + "-" + index + "-")
                .repeat(Math.max(1, snippetChars / (prefix.length() + 12)));
        return "public void method" + index + "() {\n    " + first + "\n    "
                + filler + "\n    finish" + index + "();\n}";
    }

    private int totalChars(List<ToolResponseMessage.ToolResponse> responses) {
        return responses.stream().map(ToolResponseMessage.ToolResponse::responseData)
                .mapToInt(String::length).sum();
    }

    private int batchEstimatedTokens(List<ToolResponseMessage.ToolResponse> responses) {
        EstimatedTokenCounter counter = new EstimatedTokenCounter(compressionProperties);
        return responses.stream().mapToInt(response -> counter.countText(null,
                "toolCallId: " + response.id() + "\ntoolName: " + response.name()
                        + "\nresponse:\n" + response.responseData()).tokens()).sum();
    }

    private int occurrences(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }
}
