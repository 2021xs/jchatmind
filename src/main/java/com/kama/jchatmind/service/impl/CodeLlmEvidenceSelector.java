package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.model.dto.CodeEvidenceCandidateCard;
import com.kama.jchatmind.model.dto.CodeEvidenceSelectionResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Component
public class CodeLlmEvidenceSelector {
    private final ChatClientRegistry chatClientRegistry;
    private final CodeRagProperties properties;
    private final ObjectMapper objectMapper;

    public CodeLlmEvidenceSelector(ChatClientRegistry chatClientRegistry,
                                   CodeRagProperties properties,
                                   ObjectMapper objectMapper) {
        this.chatClientRegistry = chatClientRegistry;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public CodeEvidenceSelectionResult select(String query, List<CodeEvidenceCandidateCard> candidates) {
        // Answer-time evidence selection only. This prompt is standalone and does not use ChatMemory or tools.
        long started = System.nanoTime();
        if (!properties.getLlmSelector().isEnabled()) {
            return fallbackResult(candidates, started, "llm selector disabled; fallback to candidate order");
        }

        String prompt = buildPrompt(query, candidates);
        String response = null;
        try {
            response = callModel(prompt);
            CodeEvidenceSelectionResult parsed = parseResponse(response, candidates);
            parsed.setRawResponse(response);
            parsed.setLatencyMs(elapsedMs(started));
            return parsed;
        } catch (Exception e) {
            CodeEvidenceSelectionResult fallback = fallbackResult(candidates, started,
                    "selector failed: " + e.getMessage());
            fallback.setRawResponse(response);
            fallback.setJsonParseOk(false);
            return fallback;
        }
    }

    private String callModel(String prompt) throws Exception {
        ChatClient chatClient = chatClientRegistry.get(properties.getLlmSelector().getModel());
        if (chatClient == null) {
            throw new IllegalStateException("ChatClient not found for model: " + properties.getLlmSelector().getModel());
        }
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Callable<String> task = () -> chatClient.prompt().user(prompt).call().content();
            Future<String> future = executor.submit(task);
            return future.get(properties.getLlmSelector().getTimeoutMs(), TimeUnit.MILLISECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private CodeEvidenceSelectionResult parseResponse(String response, List<CodeEvidenceCandidateCard> candidates) throws Exception {
        JsonNode root = objectMapper.readTree(extractJson(response));
        Set<String> allowedIds = new LinkedHashSet<>();
        for (CodeEvidenceCandidateCard candidate : candidates) {
            allowedIds.add(candidate.getChunkId());
        }

        List<String> selected = new ArrayList<>();
        JsonNode ids = root.path("selectedChunkIds");
        if (ids.isArray()) {
            for (JsonNode id : ids) {
                String value = id.asText("");
                if (allowedIds.contains(value) && !selected.contains(value)) {
                    selected.add(value);
                }
                if (selected.size() >= properties.getLlmSelector().getMaxSelected()) {
                    break;
                }
            }
        }
        if (selected.isEmpty()) {
            return fallbackResult(candidates, System.nanoTime(),
                    "selector returned no valid candidate chunk id");
        }
        return CodeEvidenceSelectionResult.builder()
                .selectedChunkIds(selected)
                .reason(root.path("reason").asText(""))
                .answerType(root.path("answerType").asText("UNKNOWN"))
                .jsonParseOk(true)
                .fallback(false)
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
                .reason(reason)
                .answerType("UNKNOWN")
                .jsonParseOk(true)
                .fallback(true)
                .latencyMs(elapsedMs(started))
                .build();
    }

    private String buildPrompt(String query, List<CodeEvidenceCandidateCard> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an offline code RAG evidence selector. Select the best evidence chunks from the candidate cards.\n");
        sb.append("Return ONLY compact JSON with this schema:\n");
        sb.append("{\"selectedChunkIds\":[\"...\"],\"reason\":\"brief reason\",\"answerType\":\"API|SERVICE|MAPPER|SQL|CONFIG|UTILITY|CLASS|MULTI|UNKNOWN\"}\n\n");
        sb.append("Rules:\n");
        sb.append("- API / endpoint / request handler queries: prefer CONTROLLER_API.\n");
        sb.append("- Service behavior queries: prefer SERVICE_METHOD.\n");
        sb.append("- Mapper interface queries: prefer MAPPER_METHOD.\n");
        sb.append("- SQL / XML / table / statement queries: prefer MYBATIS_SQL.\n");
        sb.append("- Config / bean queries: prefer JAVA_METHOD or CLASS_SUMMARY with relevant config evidence.\n");
        sb.append("- Select at most ").append(properties.getLlmSelector().getMaxSelected()).append(" chunks.\n");
        sb.append("- Do not invent chunk ids or facts outside the candidate cards.\n\n");
        sb.append("Query:\n").append(query).append("\n\n");
        sb.append("Candidate cards:\n");
        for (CodeEvidenceCandidateCard candidate : candidates) {
            sb.append("[chunkId=").append(safe(candidate.getChunkId())).append("]\n");
            sb.append("chunkType: ").append(safe(candidate.getChunkType())).append('\n');
            sb.append("filePath: ").append(safe(candidate.getFilePath())).append('\n');
            sb.append("symbolName: ").append(safe(candidate.getSymbolName())).append('\n');
            sb.append("apiPath: ").append(safe(candidate.getApiPath())).append('\n');
            sb.append("httpMethod: ").append(safe(candidate.getHttpMethod())).append('\n');
            sb.append("metadata: ").append(safe(candidate.getMetadataSummary())).append('\n');
            sb.append("evidenceRole: ").append(safe(candidate.getEvidenceRole())).append('\n');
            sb.append("evidenceHint: ").append(safe(candidate.getEvidenceHint())).append('\n');
            sb.append("rawRank: ").append(candidate.getRawRank()).append('\n');
            sb.append("candidateScore: ").append(candidate.getCandidateScore()).append('\n');
            sb.append("snippet: ").append(safe(candidate.getSnippet())).append("\n\n");
        }
        return sb.toString();
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
}
