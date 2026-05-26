package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.model.dto.RagSearchResult;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.tool.ToolRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class KnowledgeTools implements Tool {

    private final RagService ragService;
    private final SseService sseService;
    private final ToolRegistry toolRegistry;

    public KnowledgeTools(RagService ragService, SseService sseService, ToolRegistry toolRegistry) {
        this.ragService = ragService;
        this.sseService = sseService;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public String getName() {
        return "knowledgeQuery";
    }

    @Override
    public String getDescription() {
        return "用于从知识库执行语义检索（RAG）。输入知识库 ID 和查询文本，返回与查询最相关的内容片段。";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "knowledgeQuery",
            description = "从指定知识库中执行相似性检索（RAG）。参数为知识库 ID（kbsId）和查询文本（query），返回与查询最相关的知识片段。"
    )
    public String knowledgeQuery(String kbsId, String query) {
        List<RagSearchResult> results = ragService.similaritySearchWithMetadata(kbsId, query);
        if (results.isEmpty()) {
            return "未检索到相关知识片段。";
        }
        String formatted = results.stream()
                .map(this::formatResult)
                .collect(Collectors.joining("\n\n"));
        return toolRegistry.truncateResult(getName(), formatted);
    }

    private String formatResult(RagSearchResult result) {
        return "[source]\n"
                + "chunkId: " + nullToEmpty(result.getChunkId()) + "\n"
                + "title: " + nullToEmpty(result.getTitle()) + "\n"
                + "sourceType: " + nullToEmpty(result.getSourceType()) + "\n"
                + "sourceId: " + nullToEmpty(result.getSourceId()) + "\n"
                + "score: " + (result.getScore() == null ? "" : result.getScore()) + "\n"
                + "metadata: " + nullToEmpty(result.getMetadata()) + "\n"
                + "[content]\n"
                + nullToEmpty(result.getContent());
        /*
        return """
                [来源]
                chunkId: %s
                title: %s
                sourceType: %s
                sourceId: %s
                score: %s
                metadata: %s
                [内容]
                %s
                """.formatted(
                nullToEmpty(result.getChunkId()),
                nullToEmpty(result.getTitle()),
                nullToEmpty(result.getSourceType()),
                nullToEmpty(result.getSourceId()),
                result.getScore() == null ? "" : result.getScore(),
                nullToEmpty(result.getMetadata()),
                nullToEmpty(result.getContent())
        );
        */
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
