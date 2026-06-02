package com.kama.jchatmind.integration.feishu;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeishuAgentCommandServiceTest {

    private FeishuProperties properties;
    private FeishuMessageClient messageClient;
    private FeishuCardMessageClient cardMessageClient;
    private FeishuAgentRunAdapter agentRunAdapter;
    private FeishuAgentSessionBindingService sessionBindingService;
    private FeishuAgentCommandService service;

    @BeforeEach
    void setUp() {
        properties = new FeishuProperties();
        properties.setDefaultAgentId("11111111-1111-1111-1111-111111111111");
        messageClient = mock(FeishuMessageClient.class);
        cardMessageClient = mock(FeishuCardMessageClient.class);
        agentRunAdapter = mock(FeishuAgentRunAdapter.class);
        sessionBindingService = mock(FeishuAgentSessionBindingService.class);
        service = new FeishuAgentCommandService(properties, messageClient, cardMessageClient, agentRunAdapter,
                sessionBindingService, Runnable::run,
                Clock.fixed(Instant.parse("2026-06-01T03:00:00Z"), ZoneId.of("Asia/Shanghai")));
    }

    @Test
    void handleAgentSendsRunningCardRunsAgentAndUpdatesFinalCard() {
        when(cardMessageClient.sendAgentCard(eq("oc_test"), isA(FeishuAgentCardSnapshot.class)))
                .thenReturn("om_card");
        when(agentRunAdapter.run(eq(properties.getDefaultAgentId()), eq("oc_test"), eq("p2p"), eq("ou_test"),
                eq("analyze seckill order flow")))
                .thenReturn(new FeishuAgentRunAdapter.AgentRunResult(
                        "3b494f89-8d6b-3f2c-a61f-c65609be4bfa",
                        "user-message-id",
                        "final answer"));
        when(messageClient.sendText(eq("oc_test"), anyString())).thenReturn(true);

        service.handleAgent("oc_test", "p2p", "ou_test", "analyze seckill order flow");

        verify(cardMessageClient).sendAgentCard(eq("oc_test"), isA(FeishuAgentCardSnapshot.class));
        ArgumentCaptor<FeishuAgentCardSnapshot> updated = ArgumentCaptor.forClass(FeishuAgentCardSnapshot.class);
        verify(cardMessageClient).updateAgentCard(eq("om_card"), updated.capture());
        assertTrue(updated.getValue().getResult().contains("回答已发送"));
        assertTrue(updated.getValue().getStatus().contains("已完成"));

        ArgumentCaptor<String> answer = ArgumentCaptor.forClass(String.class);
        verify(messageClient).sendText(eq("oc_test"), answer.capture());
        assertTrue(answer.getValue().startsWith("完整回答 1/1"));
        assertTrue(answer.getValue().contains("final answer"));
    }

    @Test
    void handleAgentSendsLongAnswerInFollowupTextParts() {
        String longAnswer = ("line-1\n" + "a".repeat(3900) + "\nline-2\n" + "b".repeat(3900));
        when(cardMessageClient.sendAgentCard(eq("oc_test"), isA(FeishuAgentCardSnapshot.class)))
                .thenReturn("om_card");
        when(agentRunAdapter.run(eq(properties.getDefaultAgentId()), eq("oc_test"), eq("p2p"), eq("ou_test"),
                eq("analyze seckill order flow")))
                .thenReturn(new FeishuAgentRunAdapter.AgentRunResult(
                        "3b494f89-8d6b-3f2c-a61f-c65609be4bfa",
                        "user-message-id",
                        longAnswer));
        when(messageClient.sendText(eq("oc_test"), anyString())).thenReturn(true);

        service.handleAgent("oc_test", "p2p", "ou_test", "analyze seckill order flow");

        ArgumentCaptor<FeishuAgentCardSnapshot> updated = ArgumentCaptor.forClass(FeishuAgentCardSnapshot.class);
        verify(cardMessageClient).updateAgentCard(eq("om_card"), updated.capture());
        assertTrue(updated.getValue().getResult().contains("回答已发送"));
        assertTrue(updated.getValue().getResult().length() < 50);

        ArgumentCaptor<String> textParts = ArgumentCaptor.forClass(String.class);
        verify(messageClient, times(3)).sendText(eq("oc_test"), textParts.capture());
        List<String> messages = textParts.getAllValues();
        assertEquals(3, messages.size());
        assertTrue(messages.get(0).startsWith("完整回答 1/3"));
        assertTrue(messages.get(1).startsWith("完整回答 2/3"));
        assertTrue(messages.get(2).startsWith("完整回答 3/3"));
    }

    @Test
    void missingDefaultAgentIdSendsFriendlyMessageWithoutCard() {
        properties.setDefaultAgentId("");

        service.handleAgent("oc_test", "p2p", "ou_test", "analyze seckill order flow");

        verify(messageClient).sendText(eq("oc_test"), eq(FeishuAgentCommandService.AGENT_MISSING_CONFIG_ERROR));
        verify(cardMessageClient, never()).sendAgentCard(anyString(), isA(FeishuAgentCardSnapshot.class));
        verify(agentRunAdapter, never()).run(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void sendCardFailureFallsBackToText() {
        when(cardMessageClient.sendAgentCard(eq("oc_test"), isA(FeishuAgentCardSnapshot.class)))
                .thenThrow(new RuntimeException("permission denied"));

        service.handleAgent("oc_test", "p2p", "ou_test", "analyze seckill order flow");

        verify(messageClient).sendText(eq("oc_test"), eq(FeishuAgentCommandService.AGENT_SEND_ERROR));
        verify(agentRunAdapter, never()).run(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void agentFailureUpdatesFailedCard() {
        when(cardMessageClient.sendAgentCard(eq("oc_test"), isA(FeishuAgentCardSnapshot.class)))
                .thenReturn("om_card");
        doThrow(new RuntimeException("model unavailable"))
                .when(agentRunAdapter).run(eq(properties.getDefaultAgentId()), eq("oc_test"), eq("p2p"), eq("ou_test"),
                        eq("analyze seckill order flow"));

        service.handleAgent("oc_test", "p2p", "ou_test", "analyze seckill order flow");

        ArgumentCaptor<FeishuAgentCardSnapshot> updated = ArgumentCaptor.forClass(FeishuAgentCardSnapshot.class);
        verify(cardMessageClient).updateAgentCard(eq("om_card"), updated.capture());
        assertTrue(updated.getValue().getStatus().contains("失败"));
        assertTrue(updated.getValue().getResult().contains("model unavailable"));
    }

    @Test
    void handleNewSessionCreatesBindingAndSendsConfirmation() {
        when(sessionBindingService.createNewSession(eq("oc_test"), eq("p2p"), eq("ou_test"),
                eq(properties.getDefaultAgentId())))
                .thenReturn("33333333-3333-3333-3333-333333333333");

        service.handleNewSession("oc_test", "p2p", "ou_test");

        verify(messageClient).sendText(eq("oc_test"), eq(FeishuAgentCommandService.NEW_SESSION_CREATED_TEXT));
    }

    @Test
    void handleNewSessionRequiresDefaultAgentId() {
        properties.setDefaultAgentId("");

        service.handleNewSession("oc_test", "p2p", "ou_test");

        verify(messageClient).sendText(eq("oc_test"), eq(FeishuAgentCommandService.AGENT_MISSING_CONFIG_ERROR));
        verify(sessionBindingService, never()).createNewSession(anyString(), anyString(), anyString(), anyString());
    }
}
