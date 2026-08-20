package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.model.dto.CodeAnswerEvidenceResult;
import com.kama.jchatmind.model.dto.CodeSearchResult;
import com.kama.jchatmind.service.CodeRagAnswerEvidenceService;
import com.kama.jchatmind.tool.ToolRegistry;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CodeSearchTools implements Tool {
    private final CodeRagAnswerEvidenceService answerEvidenceService;
    private final ToolRegistry toolRegistry;
    private final CodeRagProperties codeRagProperties;
    private final CodeSearchEvidenceFormatter evidenceFormatter = new CodeSearchEvidenceFormatter();

    public CodeSearchTools(CodeRagAnswerEvidenceService answerEvidenceService,
                           ToolRegistry toolRegistry,
                           CodeRagProperties codeRagProperties) {
        this.answerEvidenceService = answerEvidenceService;
        this.toolRegistry = toolRegistry;
        this.codeRagProperties = codeRagProperties;
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
        return toolRegistry.truncateResult(getName(), evidenceFormatter.format(results));
    }
}
