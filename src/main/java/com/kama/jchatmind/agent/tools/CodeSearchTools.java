package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.agent.AgentExecutionContext;
import com.kama.jchatmind.message.AgentSseEvent;
import com.kama.jchatmind.model.dto.CodeSearchResult;
import com.kama.jchatmind.service.CodeSearchService;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.tool.ToolRegistry;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class CodeSearchTools implements Tool {
    private final CodeSearchService codeSearchService;
    private final SseService sseService;
    private final ToolRegistry toolRegistry;

    public CodeSearchTools(CodeSearchService codeSearchService, SseService sseService, ToolRegistry toolRegistry) {
        this.codeSearchService = codeSearchService;
        this.sseService = sseService;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public String getName() {
        return "searchProjectCode";
    }

    @Override
    public String getDescription() {
        return "Search imported Java backend project code chunks. Returns related code snippets and possible related paths, not an exact static call graph.";
    }

    @Override
    public ToolType getType() {
        return ToolType.OPTIONAL;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "searchProjectCode",
            description = "Search imported Java/Spring Boot backend code by repoId and natural language query. Returns related code snippets, file paths, line ranges, symbols, API paths and scores."
    )
    public String searchProjectCode(String repoId, String query, Integer topK) {
        int requestedTopK = topK == null ? 5 : topK;
        int effectiveTopK = Math.max(1, Math.min(requestedTopK, 10));
        List<CodeSearchResult> results = codeSearchService.search(repoId, query, effectiveTopK);
        sendRetrievalEvent(repoId, query, results);
        if (results.isEmpty()) {
            return "No related code snippets found. This tool provides semantic retrieval over imported code, not an exact static call graph.";
        }
        String topKNote = requestedTopK > effectiveTopK
                ? "topK was clamped from " + requestedTopK + " to " + effectiveTopK + " by tool policy.\n\n"
                : "";
        String formatted = topKNote + "Related code snippets / possible related paths:\n\n" + results.stream()
                .map(this::formatResult)
                .collect(Collectors.joining("\n\n"));
        return toolRegistry.truncateResult(getName(), formatted);
    }

    private void sendRetrievalEvent(String repoId, String query, List<CodeSearchResult> results) {
        AgentExecutionContext.Context context = AgentExecutionContext.get();
        if (context == null) {
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("stepNo", context.getStepNo());
        payload.put("stepId", context.getCurrentStepId());
        payload.put("repoId", repoId);
        payload.put("query", query);
        payload.put("results", results);
        sseService.sendEvent(context.getSessionId(), AgentSseEvent.of(
                context.getTaskId(),
                context.getSessionId(),
                AgentSseEvent.Type.RETRIEVAL_RESULT,
                payload
        ));
    }

    private String formatResult(CodeSearchResult result) {
        String lineRange = result.getStartLine() == null ? "" : result.getStartLine() + "-" + result.getEndLine();
        return "[code snippet]\n"
                + "filePath: " + nullToEmpty(result.getFilePath()) + "\n"
                + "lineRange: " + lineRange + "\n"
                + "fileType: " + nullToEmpty(result.getFileType()) + "\n"
                + "chunkType: " + nullToEmpty(result.getChunkType()) + "\n"
                + "symbolName: " + nullToEmpty(result.getSymbolName()) + "\n"
                + "apiPath: " + nullToEmpty(result.getApiPath()) + "\n"
                + "httpMethod: " + nullToEmpty(result.getHttpMethod()) + "\n"
                + "score: " + (result.getScore() == null ? "" : result.getScore()) + "\n"
                + "metadata: " + nullToEmpty(result.getMetadata()) + "\n"
                + "contentPreview:\n" + nullToEmpty(result.getContentPreview());
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
