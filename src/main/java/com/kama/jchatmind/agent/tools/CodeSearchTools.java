package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.agent.TaskEvidenceState;
import com.kama.jchatmind.model.dto.CodeAnswerEvidenceResult;
import com.kama.jchatmind.model.dto.CodeSearchResult;
import com.kama.jchatmind.service.CodeRagAnswerEvidenceService;
import org.springframework.stereotype.Component;
import org.springframework.ai.chat.model.ToolContext;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Component
@Slf4j
public class CodeSearchTools implements Tool {
    private final CodeRagAnswerEvidenceService answerEvidenceService;
    private final CodeSearchEvidenceFormatter evidenceFormatter = new CodeSearchEvidenceFormatter();

    public CodeSearchTools(CodeRagAnswerEvidenceService answerEvidenceService) {
        this.answerEvidenceService = answerEvidenceService;
    }

    @Override
    public ToolType getType() {
        return ToolType.OPTIONAL;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "searchProjectCode",
            description = "Search imported Java/Spring Boot backend code by repoId and natural language query. Returns selected code evidence with stable repoId/chunkId locators, file paths, line ranges, symbols, API paths and scores."
    )
    public String searchProjectCode(String repoId, String query, ToolContext toolContext) {
        CodeAnswerEvidenceResult evidenceResult = answerEvidenceService.retrieve(repoId, query);
        List<CodeSearchResult> results = evidenceResult.getSelectedEvidence();
        TaskEvidenceState.SearchObservation observation = observeEvidence(toolContext, repoId, query, results);
        String result;
        if (results == null || results.isEmpty()) {
            result = "No related code evidence found. This tool provides semantic retrieval over imported code, not an exact static call graph.";
        } else {
            result = evidenceFormatter.format(results);
        }
        if (observation != null) {
            Object taskId = toolContext.getContext().get(TaskEvidenceState.TASK_ID_TOOL_CONTEXT_KEY);
            log.info("Code evidence novelty: taskId={}, searchCallNumber={}, query={}, "
                            + "returnedEvidenceCount={}, newEvidenceCount={}, duplicateEvidenceCount={}, "
                            + "consecutiveNoNoveltySearches={}, guardActive={}",
                    taskId,
                    observation.searchCallNumber(),
                    query,
                    observation.returnedEvidenceCount(),
                    observation.newEvidenceCount(),
                    observation.duplicateEvidenceCount(),
                    observation.consecutiveNoNoveltySearches(),
                    observation.guardActive());
            result = observation.toToolFeedback() + "\n\n" + result;
        }
        return result;
    }

    public String searchProjectCode(String repoId, String query) {
        CodeAnswerEvidenceResult evidenceResult = answerEvidenceService.retrieve(repoId, query);
        List<CodeSearchResult> results = evidenceResult.getSelectedEvidence();
        if (results == null || results.isEmpty()) {
            return "No related code evidence found. This tool provides semantic retrieval over imported code, not an exact static call graph.";
        }
        return evidenceFormatter.format(results);
    }

    private TaskEvidenceState.SearchObservation observeEvidence(ToolContext toolContext,
                                                                String repoId,
                                                                String query,
                                                                List<CodeSearchResult> results) {
        if (toolContext == null) {
            return null;
        }
        Object state = toolContext.getContext().get(TaskEvidenceState.TOOL_CONTEXT_KEY);
        if (!(state instanceof TaskEvidenceState taskEvidenceState)) {
            return null;
        }
        return taskEvidenceState.observeSearch(repoId, query, results);
    }
}
