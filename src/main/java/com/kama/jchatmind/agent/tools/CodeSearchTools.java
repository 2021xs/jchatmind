package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.model.dto.CodeAnswerEvidenceResult;
import com.kama.jchatmind.model.dto.CodeSearchResult;
import com.kama.jchatmind.service.CodeRagAnswerEvidenceService;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.tool.ToolRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class CodeSearchTools implements Tool {
    private final CodeRagAnswerEvidenceService answerEvidenceService;
    private final SseService sseService;
    private final ToolRegistry toolRegistry;
    private final CodeRagProperties codeRagProperties;

    public CodeSearchTools(CodeRagAnswerEvidenceService answerEvidenceService,
                           SseService sseService,
                           ToolRegistry toolRegistry,
                           CodeRagProperties codeRagProperties) {
        this.answerEvidenceService = answerEvidenceService;
        this.sseService = sseService;
        this.toolRegistry = toolRegistry;
        this.codeRagProperties = codeRagProperties;
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
            description = "Search imported Java/Spring Boot backend code by repoId and natural language query. Returns selected code evidence, file paths, line ranges, symbols, API paths and scores."
    )
    public String searchProjectCode(String repoId, String query) {
        CodeAnswerEvidenceResult evidenceResult = answerEvidenceService.retrieve(repoId, query);
        List<CodeSearchResult> results = evidenceResult.getSelectedEvidence();
        if (results == null || results.isEmpty()) {
            return "No related code evidence found. This tool provides semantic retrieval over imported code, not an exact static call graph.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Selected code evidence for answering:\n");
        sb.append("rawCandidateCount: ").append(evidenceResult.getRawCount())
                .append(", selectedCount: ").append(results.size()).append('\n');
        if (codeRagProperties.getAnswerEvidence().isIncludeSelectorDebug()) {
            sb.append("selectorFallback: ").append(evidenceResult.isFallback())
                    .append(", selectorJsonParseOk: ").append(evidenceResult.isJsonParseOk())
                    .append(", selectorLatencyMs: ").append(evidenceResult.getSelectorLatencyMs())
                    .append(", answerType: ").append(nullToEmpty(evidenceResult.getAnswerType())).append('\n');
            sb.append("selectorReason: ").append(nullToEmpty(evidenceResult.getSelectorReason())).append("\n\n");
        } else {
            sb.append('\n');
        }
        sb.append(results.stream()
                .map(this::formatResult)
                .collect(Collectors.joining("\n\n")));
        return toolRegistry.truncateResult(getName(), sb.toString());
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
