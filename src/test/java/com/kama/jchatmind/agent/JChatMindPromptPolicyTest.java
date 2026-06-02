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
}
