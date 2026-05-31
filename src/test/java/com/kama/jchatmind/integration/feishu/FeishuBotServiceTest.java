package com.kama.jchatmind.integration.feishu;

import com.kama.jchatmind.model.dto.CodeAnswerEvidenceResult;
import com.kama.jchatmind.model.dto.CodeSearchResult;
import com.kama.jchatmind.service.CodeRagAnswerEvidenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeishuBotServiceTest {

    private static final String TEST_REPO_ID = "ac61fb27-e3cd-4193-9620-b6d50ef8f096";

    private FeishuMessageClient messageClient;
    private CodeRagAnswerEvidenceService codeRagAnswerEvidenceService;
    private FeishuBotService botService;

    @BeforeEach
    void setUp() {
        messageClient = mock(FeishuMessageClient.class);
        codeRagAnswerEvidenceService = mock(CodeRagAnswerEvidenceService.class);
        FeishuProperties properties = new FeishuProperties();
        properties.setRepoAliases(Map.of("hmdp", TEST_REPO_ID, "黑马点评", TEST_REPO_ID));
        botService = new FeishuBotService(new FeishuCommandParser(), messageClient, codeRagAnswerEvidenceService,
                new FeishuRepoResolver(properties));
    }

    @Test
    void helpCommandSendsHelpText() {
        botService.handleTextMessage("oc_test", "/help");

        verify(messageClient).sendText(eq("oc_test"), eq(FeishuBotService.HELP_TEXT));
        verify(codeRagAnswerEvidenceService, never()).retrieve(anyString(), anyString());
    }

    @Test
    void validAskCodeCallsCodeRagAndSendsFormattedEvidence() {
        when(codeRagAnswerEvidenceService.retrieve(TEST_REPO_ID, "秒杀订单队列在哪里定义？"))
                .thenReturn(CodeAnswerEvidenceResult.builder()
                        .selectedEvidence(List.of(CodeSearchResult.builder()
                                .filePath("src/main/java/demo/RabbitConstants.java")
                                .symbolName("SECKILL_ORDER_QUEUE")
                                .startLine(12)
                                .endLine(12)
                                .contentPreview("public static final String SECKILL_ORDER_QUEUE = \"seckill.order.queue\";")
                                .build()))
                        .build());

        botService.handleTextMessage("oc_test", "/ask-code hmdp 秒杀订单队列在哪里定义？");

        verify(codeRagAnswerEvidenceService).retrieve(TEST_REPO_ID, "秒杀订单队列在哪里定义？");
        ArgumentCaptor<String> replyCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageClient).sendText(eq("oc_test"), replyCaptor.capture());
        String reply = replyCaptor.getValue();
        assertTrue(reply.contains("问题："));
        assertTrue(reply.contains("命中证据："));
        assertTrue(reply.contains("src/main/java/demo/RabbitConstants.java"));
        assertTrue(reply.contains("SECKILL_ORDER_QUEUE"));
        assertTrue(reply.contains("结果来自 JChatMind Code RAG evidence 查询。"));
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
        when(codeRagAnswerEvidenceService.retrieve(TEST_REPO_ID, "哪里定义队列？"))
                .thenThrow(new RuntimeException("selector unavailable"));

        botService.handleTextMessage("oc_test", "/ask-code hmdp 哪里定义队列？");

        verify(messageClient).sendText(eq("oc_test"), eq(FeishuBotService.ASK_CODE_ERROR));
    }

    @Test
    void unknownRepoAliasSendsFriendlyMessageWithoutCallingCodeRag() {
        botService.handleTextMessage("oc_test", "/ask-code unknown 哪里定义队列？");

        verify(messageClient).sendText(eq("oc_test"), eq(FeishuBotService.ASK_CODE_REPO_NOT_FOUND));
        verify(codeRagAnswerEvidenceService, never()).retrieve(anyString(), anyString());
    }
}
