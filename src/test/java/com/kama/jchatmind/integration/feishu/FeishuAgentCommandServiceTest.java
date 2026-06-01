package com.kama.jchatmind.integration.feishu;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeishuAgentCommandServiceTest {

    private FeishuProperties properties;
    private FeishuMessageClient messageClient;
    private FeishuCardMessageClient cardMessageClient;
    private FeishuAgentRunAdapter agentRunAdapter;
    private FeishuAgentCommandService service;

    @BeforeEach
    void setUp() {
        properties = new FeishuProperties();
        properties.setDefaultAgentId("11111111-1111-1111-1111-111111111111");
        messageClient = mock(FeishuMessageClient.class);
        cardMessageClient = mock(FeishuCardMessageClient.class);
        agentRunAdapter = mock(FeishuAgentRunAdapter.class);
        service = new FeishuAgentCommandService(properties, messageClient, cardMessageClient, agentRunAdapter,
                Runnable::run, Clock.fixed(Instant.parse("2026-06-01T03:00:00Z"), ZoneId.of("Asia/Shanghai")));
    }

    @Test
    void handleAgentSendsRunningCardRunsAgentAndUpdatesFinalCard() {
        when(cardMessageClient.sendAgentCard(eq("oc_test"), isA(FeishuAgentCardSnapshot.class)))
                .thenReturn("om_card");
        when(agentRunAdapter.run(eq(properties.getDefaultAgentId()), eq("oc_test"), eq("分析秒杀订单链路")))
                .thenReturn(new FeishuAgentRunAdapter.AgentRunResult(
                        "3b494f89-8d6b-3f2c-a61f-c65609be4bfa",
                        "user-message-id",
                        "最终答案"));

        service.handleAgent("oc_test", "分析秒杀订单链路");

        verify(cardMessageClient).sendAgentCard(eq("oc_test"), isA(FeishuAgentCardSnapshot.class));
        ArgumentCaptor<FeishuAgentCardSnapshot> updated = ArgumentCaptor.forClass(FeishuAgentCardSnapshot.class);
        verify(cardMessageClient).updateAgentCard(eq("om_card"), updated.capture());
        assertTrue(updated.getValue().getResult().contains("最终答案"));
        assertTrue(updated.getValue().getStatus().contains("已完成"));
    }

    @Test
    void missingDefaultAgentIdSendsFriendlyMessageWithoutCard() {
        properties.setDefaultAgentId("");

        service.handleAgent("oc_test", "分析秒杀订单链路");

        verify(messageClient).sendText(eq("oc_test"), eq(FeishuAgentCommandService.AGENT_MISSING_CONFIG_ERROR));
        verify(cardMessageClient, never()).sendAgentCard(anyString(), isA(FeishuAgentCardSnapshot.class));
        verify(agentRunAdapter, never()).run(anyString(), anyString(), anyString());
    }

    @Test
    void sendCardFailureFallsBackToText() {
        when(cardMessageClient.sendAgentCard(eq("oc_test"), isA(FeishuAgentCardSnapshot.class)))
                .thenThrow(new RuntimeException("permission denied"));

        service.handleAgent("oc_test", "分析秒杀订单链路");

        verify(messageClient).sendText(eq("oc_test"), eq(FeishuAgentCommandService.AGENT_SEND_ERROR));
        verify(agentRunAdapter, never()).run(anyString(), anyString(), anyString());
    }

    @Test
    void agentFailureUpdatesFailedCard() {
        when(cardMessageClient.sendAgentCard(eq("oc_test"), isA(FeishuAgentCardSnapshot.class)))
                .thenReturn("om_card");
        doThrow(new RuntimeException("model unavailable"))
                .when(agentRunAdapter).run(eq(properties.getDefaultAgentId()), eq("oc_test"), eq("分析秒杀订单链路"));

        service.handleAgent("oc_test", "分析秒杀订单链路");

        ArgumentCaptor<FeishuAgentCardSnapshot> updated = ArgumentCaptor.forClass(FeishuAgentCardSnapshot.class);
        verify(cardMessageClient).updateAgentCard(eq("om_card"), updated.capture());
        assertTrue(updated.getValue().getStatus().contains("失败"));
        assertTrue(updated.getValue().getResult().contains("model unavailable"));
    }
}
