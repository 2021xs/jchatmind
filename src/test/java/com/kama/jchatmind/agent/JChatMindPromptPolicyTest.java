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
}
