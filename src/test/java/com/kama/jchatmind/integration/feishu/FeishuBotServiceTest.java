package com.kama.jchatmind.integration.feishu;

import com.kama.jchatmind.model.dto.CodeAnswerEvidenceResult;
import com.kama.jchatmind.model.dto.CodeSearchResult;
import com.kama.jchatmind.service.CodeRagAnswerEvidenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeishuBotServiceTest {

    private static final String TEST_REPO_ID = "ac61fb27-e3cd-4193-9620-b6d50ef8f096";

    private FeishuMessageClient messageClient;
    private FeishuCardMessageClient cardMessageClient;
    private FeishuAgentCommandService agentCommandService;
    private CodeRagAnswerEvidenceService codeRagAnswerEvidenceService;
    private FeishuBotService botService;

    @BeforeEach
    void setUp() {
        messageClient = mock(FeishuMessageClient.class);
        cardMessageClient = mock(FeishuCardMessageClient.class);
        agentCommandService = mock(FeishuAgentCommandService.class);
        codeRagAnswerEvidenceService = mock(CodeRagAnswerEvidenceService.class);
        FeishuProperties properties = new FeishuProperties();
        properties.setRepoAliases(Map.of("hmdp", TEST_REPO_ID, "heima", TEST_REPO_ID));
        botService = new FeishuBotService(new FeishuCommandParser(), messageClient, codeRagAnswerEvidenceService,
                new FeishuRepoResolver(properties), cardMessageClient, agentCommandService, Runnable::run,
                Clock.fixed(Instant.parse("2026-06-01T03:00:00Z"), ZoneId.of("Asia/Shanghai")), 0L);
    }

    @Test
    void helpCommandSendsHelpText() {
        botService.handleTextMessage("oc_test", "/help");

        verify(messageClient).sendText(eq("oc_test"), eq(FeishuBotService.HELP_TEXT));
        verify(codeRagAnswerEvidenceService, never()).retrieve(anyString(), anyString());
    }

    @Test
    void validAskCodeCallsCodeRagAndSendsFormattedEvidence() {
        when(codeRagAnswerEvidenceService.retrieve(TEST_REPO_ID, "where is queue defined"))
                .thenReturn(CodeAnswerEvidenceResult.builder()
                        .selectedEvidence(List.of(CodeSearchResult.builder()
                                .filePath("src/main/java/demo/RabbitConstants.java")
                                .symbolName("SECKILL_ORDER_QUEUE")
                                .startLine(12)
                                .endLine(12)
                                .contentPreview("public static final String SECKILL_ORDER_QUEUE = \"seckill.order.queue\";")
                                .build()))
                        .build());

        botService.handleTextMessage("oc_test", "/ask-code hmdp where is queue defined");

        verify(codeRagAnswerEvidenceService).retrieve(TEST_REPO_ID, "where is queue defined");
        ArgumentCaptor<String> replyCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageClient).sendText(eq("oc_test"), replyCaptor.capture());
        String reply = replyCaptor.getValue();
        assertTrue(reply.contains("src/main/java/demo/RabbitConstants.java"));
        assertTrue(reply.contains("SECKILL_ORDER_QUEUE"));
    }

    @Test
    void invalidAskCodeSendsUsageWithoutCallingCodeRag() {
        botService.handleTextMessage("oc_test", "/ask-code hmdp");

        verify(messageClient).sendText(eq("oc_test"), eq(FeishuBotService.ASK_CODE_USAGE));
        verify(codeRagAnswerEvidenceService, never()).retrieve(anyString(), anyString());
    }

    @Test
    void ordinaryTextIsIgnored() {
        botService.handleTextMessage("oc_test", "hello");

        verify(messageClient, never()).sendText(anyString(), anyString());
        verify(codeRagAnswerEvidenceService, never()).retrieve(anyString(), anyString());
    }

    @Test
    void codeRagFailureSendsFriendlyError() {
        when(codeRagAnswerEvidenceService.retrieve(TEST_REPO_ID, "where is queue"))
                .thenThrow(new RuntimeException("selector unavailable"));

        botService.handleTextMessage("oc_test", "/ask-code hmdp where is queue");

        verify(messageClient).sendText(eq("oc_test"), eq(FeishuBotService.ASK_CODE_ERROR));
    }

    @Test
    void unknownRepoAliasSendsFriendlyMessageWithoutCallingCodeRag() {
        botService.handleTextMessage("oc_test", "/ask-code unknown where is queue");

        verify(messageClient).sendText(eq("oc_test"), eq(FeishuBotService.ASK_CODE_REPO_NOT_FOUND));
        verify(codeRagAnswerEvidenceService, never()).retrieve(anyString(), anyString());
    }

    @Test
    void validAgentTestSendsAndUpdatesCard() {
        when(cardMessageClient.sendAgentCard(eq("oc_test"), isA(FeishuAgentCardSnapshot.class)))
                .thenReturn("om_card");
        doNothing().when(cardMessageClient).updateAgentCard(eq("om_card"), isA(FeishuAgentCardSnapshot.class));

        botService.handleTextMessage("oc_test", "/agent-test analyze seckill order flow");

        verify(cardMessageClient).sendAgentCard(eq("oc_test"), isA(FeishuAgentCardSnapshot.class));
        verify(cardMessageClient).updateAgentCard(eq("om_card"), isA(FeishuAgentCardSnapshot.class));
    }

    @Test
    void invalidAgentTestSendsUsageWithoutCallingCardClient() {
        botService.handleTextMessage("oc_test", "/agent-test");

        verify(messageClient).sendText(eq("oc_test"), eq(FeishuBotService.AGENT_TEST_USAGE));
        verify(cardMessageClient, never()).sendAgentCard(anyString(), isA(FeishuAgentCardSnapshot.class));
    }

    @Test
    void validAgentCommandDelegatesToAgentCommandService() {
        botService.handleTextMessage("oc_test", "p2p", "ou_test", "/agent analyze seckill order flow");

        verify(agentCommandService).handleAgent(eq("oc_test"), eq("p2p"), eq("ou_test"),
                eq("analyze seckill order flow"));
        verify(codeRagAnswerEvidenceService, never()).retrieve(anyString(), anyString());
    }

    @Test
    void newSessionCommandDelegatesToAgentCommandService() {
        botService.handleTextMessage("oc_test", "p2p", "ou_test", "/new-session");

        verify(agentCommandService).handleNewSession(eq("oc_test"), eq("p2p"), eq("ou_test"));
    }

    @Test
    void invalidAgentCommandSendsUsage() {
        botService.handleTextMessage("oc_test", "/agent");

        verify(messageClient).sendText(eq("oc_test"), eq(FeishuAgentCommandService.AGENT_USAGE));
        verify(agentCommandService, never()).handleAgent(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void agentTestSendFailureFallsBackToTextMessage() {
        when(cardMessageClient.sendAgentCard(eq("oc_test"), isA(FeishuAgentCardSnapshot.class)))
                .thenThrow(new RuntimeException("access denied"));

        botService.handleTextMessage("oc_test", "/agent-test analyze seckill order flow");

        verify(messageClient).sendText(eq("oc_test"), eq(FeishuBotService.AGENT_TEST_SEND_ERROR));
        verify(cardMessageClient, never()).updateAgentCard(anyString(), isA(FeishuAgentCardSnapshot.class));
    }

    @Test
    void agentTestUpdateFailureDoesNotThrow() {
        when(cardMessageClient.sendAgentCard(eq("oc_test"), isA(FeishuAgentCardSnapshot.class)))
                .thenReturn("om_card");
        doThrow(new RuntimeException("update denied"))
                .when(cardMessageClient).updateAgentCard(eq("om_card"), isA(FeishuAgentCardSnapshot.class));

        botService.handleTextMessage("oc_test", "/agent-test analyze seckill order flow");

        verify(cardMessageClient).sendAgentCard(eq("oc_test"), isA(FeishuAgentCardSnapshot.class));
        verify(cardMessageClient).updateAgentCard(eq("om_card"), isA(FeishuAgentCardSnapshot.class));
    }

    @Test
    void askCodeReplyKeepsLongerSnippet() {
        String longSnippet = "x".repeat(500);
        when(codeRagAnswerEvidenceService.retrieve(TEST_REPO_ID, "where is queue"))
                .thenReturn(CodeAnswerEvidenceResult.builder()
                        .selectedEvidence(List.of(CodeSearchResult.builder()
                                .filePath("src/main/java/demo/RabbitConstants.java")
                                .contentPreview(longSnippet)
                                .build()))
                        .build());

        botService.handleTextMessage("oc_test", "/ask-code hmdp where is queue");

        ArgumentCaptor<String> replyCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageClient).sendText(eq("oc_test"), replyCaptor.capture());
        assertTrue(replyCaptor.getValue().contains(longSnippet));
    }
}
