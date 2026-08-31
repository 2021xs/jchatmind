package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.agent.TaskEvidenceState;
import com.kama.jchatmind.agent.observability.AgentLifecycleObservationPublisher;
import com.kama.jchatmind.model.dto.CodeAnswerEvidenceResult;
import com.kama.jchatmind.model.dto.CodeRagExecutionResult;
import com.kama.jchatmind.model.dto.CodeSearchResult;
import com.kama.jchatmind.service.CodeRagAnswerEvidenceService;
import org.springframework.stereotype.Component;
import org.springframework.ai.chat.model.ToolContext;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Collectors;

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
        CodeRagExecutionResult diagnosticExecution = null;
        CodeAnswerEvidenceResult evidenceResult;
        if (AgentLifecycleObservationPublisher.isSelectorProvenanceObservationEnabled()) {
            diagnosticExecution = answerEvidenceService.execute(repoId, query);
            evidenceResult = diagnosticExecution.getAnswerEvidence();
        } else {
            evidenceResult = answerEvidenceService.retrieve(repoId, query);
        }
        List<CodeSearchResult> results = evidenceResult.getSelectedEvidence();
        publishSelectorProvenance(toolContext, query, diagnosticExecution, results);
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

    private void publishSelectorProvenance(ToolContext toolContext,
                                           String query,
                                           CodeRagExecutionResult execution,
                                           List<CodeSearchResult> selectedResults) {
        if (execution == null || toolContext == null) {
            return;
        }
        try {
            List<CodeSearchResult> raw = execution.getRawCandidates() == null
                    ? List.of() : execution.getRawCandidates();
            List<CodeSearchResult> selected = selectedResults == null ? List.of() : selectedResults;
            Set<String> selectedChunkIds = selected.stream()
                    .map(CodeSearchResult::getChunkId)
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toSet());
            List<AgentLifecycleObservationPublisher.CodeEvidenceIdentity> rawIdentities = identities(raw);
            List<AgentLifecycleObservationPublisher.CodeEvidenceIdentity> selectedIdentities = identities(selected);
            List<AgentLifecycleObservationPublisher.CodeEvidenceIdentity> rejectedIdentities = IntStream.range(0, raw.size())
                    .filter(index -> !selectedChunkIds.contains(raw.get(index).getChunkId()))
                    .mapToObj(index -> identity(raw.get(index), index + 1))
                    .toList();
            Object taskId = toolContext.getContext().get(TaskEvidenceState.TASK_ID_TOOL_CONTEXT_KEY);
            Object sessionId = toolContext.getContext().get(
                    AgentLifecycleObservationPublisher.DIAGNOSTIC_SESSION_ID_CONTEXT_KEY);
            Object toolCallId = toolContext.getContext().get(
                    AgentLifecycleObservationPublisher.DIAGNOSTIC_TOOL_CALL_ID_CONTEXT_KEY);
            AgentLifecycleObservationPublisher.publishSelectorProvenance(
                    new AgentLifecycleObservationPublisher.SelectorProvenanceObservation(
                            stringValue(taskId), stringValue(sessionId), stringValue(toolCallId), query,
                            rawIdentities, rawIdentities, selectedIdentities, rejectedIdentities));
        } catch (RuntimeException error) {
            log.warn("Unable to capture benchmark selector provenance; continuing with unchanged tool result", error);
        }
    }

    private List<AgentLifecycleObservationPublisher.CodeEvidenceIdentity> identities(
            List<CodeSearchResult> results) {
        return IntStream.range(0, results.size())
                .mapToObj(index -> identity(results.get(index), index + 1))
                .toList();
    }

    private AgentLifecycleObservationPublisher.CodeEvidenceIdentity identity(
            CodeSearchResult result, int rank) {
        return new AgentLifecycleObservationPublisher.CodeEvidenceIdentity(
                result.getRepoId(), result.getChunkId(), result.getFilePath(), result.getSymbolName(),
                rank, result.getFinalScore() == null ? result.getScore() : result.getFinalScore());
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
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
