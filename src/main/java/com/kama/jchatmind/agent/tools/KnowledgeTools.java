package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.model.dto.RagSearchResult;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.tool.ToolRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class KnowledgeTools implements Tool {

    private final RagService ragService;
    private final ToolRegistry toolRegistry;

    public KnowledgeTools(RagService ragService, ToolRegistry toolRegistry) {
        this.ragService = ragService;
        this.toolRegistry = toolRegistry;
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
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
