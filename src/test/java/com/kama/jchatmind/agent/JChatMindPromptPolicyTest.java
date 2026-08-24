package com.kama.jchatmind.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JChatMindPromptPolicyTest {

    @Test
    void thinkPromptGuidesMacroFlowQuestionsAwayFromLocalDetailLoops() {
        String prompt = JChatMind.buildThinkPrompt(List.of());

        assertThat(prompt).contains("macro questions");
        assertThat(prompt).contains("answer the main path first");
        assertThat(prompt).contains("entry point, core service method, persistence, messaging, consumer");
        assertThat(prompt).contains("Do not let local details");
        assertThat(prompt).contains("stop calling tools and produce a concise final answer");
    }

    @Test
    void thinkPromptAddsSoftReminderWhenApproachingStepLimit() {
        String prompt = JChatMind.buildThinkPrompt(List.of(), 10, 12);

        assertThat(prompt).contains("approaching the tool-call round limit");
        assertThat(prompt).contains("Only call another tool if it is essential");
        assertThat(prompt).doesNotContain("This is the final reasoning round");
    }

    @Test
    void thinkPromptRequiresAnswerOnFinalStep() {
        String prompt = JChatMind.buildThinkPrompt(List.of(), 12, 12);

        assertThat(prompt).contains("This is the final reasoning round");
        assertThat(prompt).contains("Do not call any tool");
        assertThat(prompt).contains("You must answer now");
    }

    @Test
    void finalStreamingPlanningPromptExposesEvidenceNoveltyAndEssentialStopPolicy() {
        TaskEvidenceState state = new TaskEvidenceState();
        state.observeSearch("repo-1", "script", List.of(
                com.kama.jchatmind.model.dto.CodeSearchResult.builder()
                        .chunkId("chunk-1")
                        .repoId("repo-1")
                        .filePath("src/main/resources/script.lua")
                        .startLine(1)
                        .endLine(47)
                        .build()));

        String prompt = JChatMind.buildPlanningPrompt(List.of(), 3, 12, 4, state.snapshot());

        assertThat(prompt).contains("Planning round: 3 / 12");
        assertThat(prompt).contains("Remaining step/tool budget: 9");
        assertThat(prompt).contains("Code search calls so far: 1");
        assertThat(prompt).contains("Last search newEvidenceCount: 1");
        assertThat(prompt).contains("Consecutive no-novelty searches: 0");
        assertThat(prompt).contains("ESSENTIAL evidence gap");
        assertThat(prompt).contains("Do not delay Final for OPTIONAL context");
        assertThat(prompt).contains("src/main/resources/script.lua", "lines: 1-47");
        assertThat(prompt).doesNotContain("evidence content");
    }
}
