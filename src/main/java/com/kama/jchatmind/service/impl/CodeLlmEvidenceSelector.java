package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.model.dto.CodeEvidenceCandidateCard;
import com.kama.jchatmind.model.dto.CodeEvidenceSelectionResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class CodeLlmEvidenceSelector {
    private final ChatClientRegistry chatClientRegistry;
    private final CodeRagProperties properties;
    private final ObjectMapper objectMapper;
    private final AsyncTaskExecutor executor;
    private final CodeEvidenceCandidateFormatter candidateFormatter;

    public CodeLlmEvidenceSelector(ChatClientRegistry chatClientRegistry,
                                   CodeRagProperties properties,
                                   ObjectMapper objectMapper,
                                   @Qualifier("codeEvidenceSelectorExecutor") AsyncTaskExecutor executor) {
        this.chatClientRegistry = chatClientRegistry;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.candidateFormatter = new CodeEvidenceCandidateFormatter(objectMapper);
    }

    public CodeEvidenceSelectionResult select(String query, List<CodeEvidenceCandidateCard> candidates) {
        // Answer-time evidence selection only. This prompt is standalone and does not use ChatMemory or tools.
        long started = System.nanoTime();
        if (!properties.getLlmSelector().isEnabled()) {
            return fallbackResult(candidates, started, "llm selector disabled; fallback to candidate order");
        }

        List<LocalCandidate> localCandidates = localCandidates(candidates);
        PromptPayload prompt = buildPrompt(query, localCandidates);
        ModelCallResult modelCall;
        try {
            modelCall = callModel(prompt.text());
        } catch (Exception e) {
            CodeEvidenceSelectionResult fallback = fallbackResult(candidates, started,
                    "selector execution failed: " + e.getMessage());
            fallback.setJsonParseOk(false);
            fallback.setExecutionError(true);
            applyPromptSize(fallback, prompt);
            return fallback;
        }
        String response = modelCall.content();
        try {
            CodeEvidenceSelectionResult parsed = parseResponse(response, candidates, localCandidates);
            parsed.setRawResponse(response);
            parsed.setLatencyMs(elapsedMs(started));
            applyUsage(parsed, modelCall);
            applyPromptSize(parsed, prompt);
            return parsed;
        } catch (Exception e) {
            CodeEvidenceSelectionResult fallback = fallbackResult(candidates, started,
                    "selector response parse failed: " + e.getMessage());
            fallback.setRawResponse(response);
            fallback.setJsonParseOk(false);
            fallback.setExecutionError(false);
            applyUsage(fallback, modelCall);
            applyPromptSize(fallback, prompt);
            return fallback;
        }
    }

    private ModelCallResult callModel(String prompt) throws Exception {
        ChatClient chatClient = chatClientRegistry.get(properties.getLlmSelector().getModel());
        if (chatClient == null) {
            throw new IllegalStateException("ChatClient not found for model: " + properties.getLlmSelector().getModel());
        }
        Future<ModelCallResult> future = executor.submit(() -> toModelCallResult(
                chatClient.prompt().user(prompt).call().chatResponse()));
        try {
            return future.get(properties.getLlmSelector().getTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new TimeoutException("selector timed out after "
                    + properties.getLlmSelector().getTimeoutMs() + " ms");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception e) {
            future.cancel(true);
            throw e;
        }
    }

    static ModelCallResult toModelCallResult(ChatResponse response) {
        String content = response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                ? null
                : response.getResult().getOutput().getText();
        Usage usage = response == null || response.getMetadata() == null
                ? null
                : response.getMetadata().getUsage();
        Integer promptTokens = usage == null ? null : usage.getPromptTokens();
        Integer completionTokens = usage == null ? null : usage.getCompletionTokens();
        Integer totalTokens = usage == null ? null : usage.getTotalTokens();
        boolean usageAvailable = promptTokens != null || completionTokens != null || totalTokens != null;
        return new ModelCallResult(content, promptTokens, completionTokens, totalTokens, usageAvailable);
    }

    private void applyUsage(CodeEvidenceSelectionResult result, ModelCallResult modelCall) {
        result.setPromptTokens(modelCall.promptTokens());
        result.setCompletionTokens(modelCall.completionTokens());
        result.setTotalTokens(modelCall.totalTokens());
        result.setUsageAvailable(modelCall.usageAvailable());
    }

    private void applyPromptSize(CodeEvidenceSelectionResult result, PromptPayload prompt) {
        result.setPromptChars(prompt.text().length());
        result.setCandidateSectionChars(prompt.candidateSectionChars());
    }

    private CodeEvidenceSelectionResult parseResponse(String response,
                                                      List<CodeEvidenceCandidateCard> candidates,
                                                      List<LocalCandidate> localCandidates) throws Exception {
        JsonNode root = objectMapper.readTree(extractJson(response));
        Map<String, LocalCandidate> allowedIds = new LinkedHashMap<>();
        for (LocalCandidate candidate : localCandidates) {
            allowedIds.put(candidate.candidateId(), candidate);
        }

        List<String> proposedCandidateIds = new ArrayList<>();
        List<String> validCandidateIds = new ArrayList<>();
        List<String> invalidCandidateIds = new ArrayList<>();
        List<String> proposedChunkIds = new ArrayList<>();
        List<String> selectedChunkIds = new ArrayList<>();
        JsonNode ids = root.path("selectedCandidateIds");
        if (ids.isArray()) {
            for (JsonNode id : ids) {
                String value = id.asText("");
                if (!value.isBlank() && !proposedCandidateIds.contains(value)) {
                    proposedCandidateIds.add(value);
                    LocalCandidate localCandidate = allowedIds.get(value);
                    if (localCandidate != null) {
                        proposedChunkIds.add(localCandidate.card().getChunkId());
                        if (selectedChunkIds.size() < properties.getLlmSelector().getMaxSelected()) {
                            validCandidateIds.add(value);
                            selectedChunkIds.add(localCandidate.card().getChunkId());
                        }
                    } else {
                        invalidCandidateIds.add(value);
                    }
                }
            }
        }
        if (selectedChunkIds.isEmpty()) {
            CodeEvidenceSelectionResult fallback = fallbackResult(candidates, System.nanoTime(),
                    "selector returned no valid candidate chunk id");
            fallback.setProposedChunkIds(proposedChunkIds);
            fallback.setValidChunkIds(List.of());
            fallback.setInvalidChunkIds(List.of());
            fallback.setProposedCandidateIds(proposedCandidateIds);
            fallback.setValidCandidateIds(List.of());
            fallback.setInvalidCandidateIds(invalidCandidateIds);
            fallback.setEmptySelectorResult(proposedCandidateIds.isEmpty());
            return fallback;
        }
        return CodeEvidenceSelectionResult.builder()
                .selectedChunkIds(selectedChunkIds)
                .proposedChunkIds(proposedChunkIds)
                .validChunkIds(selectedChunkIds)
                .invalidChunkIds(List.of())
                .proposedCandidateIds(proposedCandidateIds)
                .validCandidateIds(validCandidateIds)
                .invalidCandidateIds(invalidCandidateIds)
                .reason("")
                .answerType("UNKNOWN")
                .jsonParseOk(true)
                .fallback(false)
                .emptySelectorResult(false)
                .executionError(false)
                .build();
    }

    private CodeEvidenceSelectionResult fallbackResult(List<CodeEvidenceCandidateCard> candidates, long started, String reason) {
        int limit = Math.min(properties.getLlmSelector().getMaxSelected(), candidates.size());
        List<String> selected = candidates.stream()
                .limit(limit)
                .map(CodeEvidenceCandidateCard::getChunkId)
                .toList();
        return CodeEvidenceSelectionResult.builder()
                .selectedChunkIds(selected)
                .proposedChunkIds(List.of())
                .validChunkIds(List.of())
                .invalidChunkIds(List.of())
                .proposedCandidateIds(List.of())
                .validCandidateIds(List.of())
                .invalidCandidateIds(List.of())
                .reason(reason)
                .answerType("UNKNOWN")
                .jsonParseOk(true)
                .fallback(true)
                .emptySelectorResult(false)
                .executionError(false)
                .latencyMs(elapsedMs(started))
                .build();
    }

    String buildPromptForCandidates(String query, List<CodeEvidenceCandidateCard> candidates) {
        return buildPrompt(query, localCandidates(candidates)).text();
    }

    private PromptPayload buildPrompt(String query, List<LocalCandidate> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an offline code RAG evidence selector. Select the best evidence chunks from the candidate cards.\n");
        sb.append("Return ONLY compact JSON with this schema:\n");
        sb.append("{\"selectedCandidateIds\":[\"C03\",\"C08\"]}\n\n");
        sb.append("Rules:\n");
        sb.append("- API / endpoint / request handler queries: prefer CONTROLLER_API.\n");
        sb.append("- Service behavior queries: prefer SERVICE_METHOD.\n");
        sb.append("- Mapper interface queries: prefer MAPPER_METHOD.\n");
        sb.append("- SQL / XML / table / statement queries: prefer MYBATIS_SQL.\n");
        sb.append("- Config / bean queries: prefer JAVA_METHOD or CLASS_SUMMARY with relevant config evidence.\n");
        sb.append("- Field, constant, static initializer, injected config, or class dependency queries: prefer JAVA_CLASS_MEMBER.\n");
        sb.append("- Select at most ").append(properties.getLlmSelector().getMaxSelected()).append(" candidates.\n");
        sb.append("- Return candidate ids in descending relevance order.\n");
        sb.append("- Select only ids shown in the candidate cards, formatted as C01 through C20.\n");
        sb.append("- Do not invent candidate ids or facts outside the candidate cards.\n");
        sb.append("- If no candidate is relevant, return an empty selectedCandidateIds array.\n");
        sb.append("- Return JSON only, with no Markdown, explanation, or extra fields.\n\n");
        sb.append("Query:\n").append(query).append("\n\n");
        sb.append("Candidate cards:\n");
        int candidateSectionStart = sb.length();
        for (LocalCandidate localCandidate : candidates) {
            sb.append(candidateFormatter.format(localCandidate.candidateId(), localCandidate.card()));
        }
        return new PromptPayload(sb.toString(), sb.length() - candidateSectionStart);
    }

    private List<LocalCandidate> localCandidates(List<CodeEvidenceCandidateCard> candidates) {
        List<LocalCandidate> localCandidates = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            localCandidates.add(new LocalCandidate(String.format(java.util.Locale.ROOT, "C%02d", i + 1),
                    candidates.get(i)));
        }
        return localCandidates;
    }

    private String extractJson(String response) {
        String text = response == null ? "" : response.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end >= start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    record ModelCallResult(String content, Integer promptTokens, Integer completionTokens,
                           Integer totalTokens, boolean usageAvailable) {
    }

    private record PromptPayload(String text, int candidateSectionChars) {
    }

    record LocalCandidate(String candidateId, CodeEvidenceCandidateCard card) {
    }
}
