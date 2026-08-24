package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.model.dto.CodeEvidenceCandidateCard;
import com.kama.jchatmind.model.dto.CodeEvidenceSelectionResult;
import com.kama.jchatmind.model.dto.SelectorModelResponse;
import com.kama.jchatmind.service.LlmSelectorClient;
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
    private final LlmSelectorClient llmSelectorClient;
    private final CodeRagProperties properties;
    private final ObjectMapper objectMapper;
    private final AsyncTaskExecutor executor;
    private final CodeEvidenceCandidateFormatter candidateFormatter;

    public CodeLlmEvidenceSelector(LlmSelectorClient llmSelectorClient,
                                   CodeRagProperties properties,
                                   ObjectMapper objectMapper,
                                   @Qualifier("codeEvidenceSelectorExecutor") AsyncTaskExecutor executor) {
        this.llmSelectorClient = llmSelectorClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.candidateFormatter = new CodeEvidenceCandidateFormatter(objectMapper);
    }

    public CodeEvidenceSelectionResult select(String query, List<CodeEvidenceCandidateCard> candidates) {
        // Answer-time evidence selection only. This prompt is standalone and does not use ChatMemory or tools.
        long started = System.nanoTime();
        if (!properties.getLlmSelector().isEnabled()) {
            return fallbackResult(candidates, started,
                    "SELECTOR_DISABLED: llm selector disabled; fallback to candidate order");
        }

        List<LocalCandidate> localCandidates = localCandidates(candidates);
        PromptPayload prompt = buildPrompt(query, localCandidates);
        SelectorModelResponse modelCall;
        try {
            modelCall = callModel(prompt.text());
        } catch (Exception e) {
            CodeEvidenceSelectionResult fallback = fallbackResult(candidates, started,
                    executionFailureReason(e));
            fallback.setJsonParseOk(false);
            fallback.setExecutionError(true);
            applyPromptSize(fallback, prompt);
            return fallback;
        }
        String response = modelCall.getContent();
        try {
            CodeEvidenceSelectionResult parsed = parseResponse(response, candidates, localCandidates);
            parsed.setRawResponse(response);
            parsed.setLatencyMs(elapsedMs(started));
            applyUsage(parsed, modelCall);
            applyResponseDiagnostics(parsed, modelCall);
            applyPromptSize(parsed, prompt);
            applyResponseSize(parsed, response);
            return parsed;
        } catch (Exception e) {
            CodeEvidenceSelectionResult fallback = fallbackResult(candidates, started,
                    "JSON_PARSE_ERROR: selector response parse failed: " + e.getMessage());
            fallback.setRawResponse(response);
            fallback.setJsonParseOk(false);
            fallback.setExecutionError(false);
            applyUsage(fallback, modelCall);
            applyResponseDiagnostics(fallback, modelCall);
            applyPromptSize(fallback, prompt);
            applyResponseSize(fallback, response);
            return fallback;
        }
    }

    private String executionFailureReason(Exception exception) {
        String message = exception.getMessage();
        String normalized = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        String category = exception instanceof TimeoutException || normalized.contains("timed out")
                ? "SELECTOR_TIMEOUT"
                : "MODEL_ERROR";
        return category + ": selector execution failed: " + message;
    }

    private SelectorModelResponse callModel(String prompt) throws Exception {
        Future<SelectorModelResponse> future = executor.submit(() -> llmSelectorClient.call(prompt));
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

    private void applyUsage(CodeEvidenceSelectionResult result, SelectorModelResponse modelCall) {
        result.setPromptTokens(modelCall.getPromptTokens());
        result.setCompletionTokens(modelCall.getCompletionTokens());
        result.setTotalTokens(modelCall.getTotalTokens());
        result.setUsageAvailable(modelCall.getPromptTokens() != null
                || modelCall.getCompletionTokens() != null
                || modelCall.getTotalTokens() != null);
    }

    private void applyResponseDiagnostics(CodeEvidenceSelectionResult result, SelectorModelResponse modelCall) {
        result.setReasoningContentChars(modelCall.getReasoningContentChars());
        result.setReasoningContentPresent(modelCall.getReasoningContentPresent());
        result.setFinishReason(modelCall.getFinishReason());
    }

    private void applyPromptSize(CodeEvidenceSelectionResult result, PromptPayload prompt) {
        result.setPromptChars(prompt.text().length());
        result.setCandidateSectionChars(prompt.candidateSectionChars());
    }

    private void applyResponseSize(CodeEvidenceSelectionResult result, String response) {
        result.setResponseChars(response == null ? 0 : response.length());
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
                    invalidCandidateIds.isEmpty()
                            ? "EMPTY_SELECTION: selector returned no valid candidate chunk id"
                            : "INVALID_CANDIDATE_ID: selector returned no valid candidate chunk id");
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
        sb.append("- Candidate cards use local ids such as C01, C02, and so on.\n");
        sb.append("- Select only ids shown in the candidate cards.\n");
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

    private record PromptPayload(String text, int candidateSectionChars) {
    }

    record LocalCandidate(String candidateId, CodeEvidenceCandidateCard card) {
    }
}
