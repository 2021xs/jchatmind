package com.kama.jchatmind.integration.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.model.dto.CodeAnswerEvidenceResult;
import com.kama.jchatmind.model.dto.CodeSearchResult;
import com.kama.jchatmind.service.CodeRagAnswerEvidenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FeishuEventControllerTest {

    private static final String TEST_REPO_ID = "ac61fb27-e3cd-4193-9620-b6d50ef8f096";

    private MockMvc mockMvc;
    private FeishuMessageClient messageClient;
    private FeishuAgentCommandService agentCommandService;
    private CodeRagAnswerEvidenceService codeRagAnswerEvidenceService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        FeishuProperties properties = new FeishuProperties();
        properties.setVerificationToken("test-token");
        properties.setRepoAliases(Map.of("hmdp", TEST_REPO_ID));
        messageClient = mock(FeishuMessageClient.class);
        FeishuCardMessageClient cardMessageClient = mock(FeishuCardMessageClient.class);
        codeRagAnswerEvidenceService = mock(CodeRagAnswerEvidenceService.class);
        agentCommandService = mock(FeishuAgentCommandService.class);
        FeishuBotService botService = new FeishuBotService(
                new FeishuCommandParser(), messageClient, codeRagAnswerEvidenceService,
                new FeishuRepoResolver(properties), cardMessageClient, agentCommandService, Runnable::run,
                java.time.Clock.systemDefaultZone(), 0L);
        FeishuMessageEventHandler messageEventHandler = new FeishuMessageEventHandler(objectMapper, botService, Runnable::run);
        FeishuEventController controller = new FeishuEventController(objectMapper, properties, messageEventHandler);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void urlVerificationReturnsChallengeWhenTokenMatches() throws Exception {
        mockMvc.perform(post("/api/feishu/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "url_verification",
                                  "token": "test-token",
                                  "challenge": "abc"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challenge").value("abc"));
    }

    @Test
    void urlVerificationRejectsInvalidToken() throws Exception {
        mockMvc.perform(post("/api/feishu/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "url_verification",
                                  "token": "wrong-token",
                                  "challenge": "abc"
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void nonUrlVerificationEventReturnsOkCode() throws Exception {
        mockMvc.perform(post("/api/feishu/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "schema": "2.0",
                                  "header": {
                                    "event_type": "im.message.receive_v1"
                                  },
                                  "event": {}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(messageClient, never()).sendText(anyString(), anyString());
    }

    @Test
    void messageReceiveHelpTextEventSendsHelpText() throws Exception {
        mockMvc.perform(post("/api/feishu/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textEvent("om_help", "/help")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(messageClient).sendText(eq("oc_test"), eq(FeishuBotService.HELP_TEXT));
    }

    @Test
    void messageReceiveAskCodeTextEventReturnsOkAndSendsEvidence() throws Exception {
        when(codeRagAnswerEvidenceService.retrieve(TEST_REPO_ID, "where is queue"))
                .thenReturn(CodeAnswerEvidenceResult.builder()
                        .selectedEvidence(List.of(CodeSearchResult.builder()
                                .filePath("src/main/java/demo/RabbitConstants.java")
                                .symbolName("SECKILL_ORDER_QUEUE")
                                .contentPreview("public static final String SECKILL_ORDER_QUEUE = \"seckill.order.queue\";")
                                .build()))
                        .build());

        mockMvc.perform(post("/api/feishu/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textEvent("om_ask_code", "/ask-code hmdp where is queue")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(codeRagAnswerEvidenceService).retrieve(eq(TEST_REPO_ID), eq("where is queue"));
        verify(messageClient).sendText(eq("oc_test"), org.mockito.ArgumentMatchers.contains("SECKILL_ORDER_QUEUE"));
    }

    @Test
    void messageReceiveAskCodeFailureStillReturnsOkCode() throws Exception {
        when(codeRagAnswerEvidenceService.retrieve(TEST_REPO_ID, "where is queue"))
                .thenThrow(new RuntimeException("selector unavailable"));

        mockMvc.perform(post("/api/feishu/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textEvent("om_ask_code_failed", "/ask-code hmdp where is queue")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(messageClient).sendText(eq("oc_test"), eq(FeishuBotService.ASK_CODE_ERROR));
    }

    @Test
    void messageReceiveAgentTextEventReturnsOkAndStartsAgentCommand() throws Exception {
        mockMvc.perform(post("/api/feishu/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textEvent("om_agent", "/agent analyze seckill order flow")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(agentCommandService).handleAgent(eq("oc_test"), eq("p2p"), eq("ou_test"),
                eq("analyze seckill order flow"));
    }

    @Test
    void messageReceiveNewSessionTextEventReturnsOkAndStartsNewSessionCommand() throws Exception {
        mockMvc.perform(post("/api/feishu/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textEvent("om_new_session", "/new-session")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(agentCommandService).handleNewSession(eq("oc_test"), eq("p2p"), eq("ou_test"));
    }

    @Test
    void duplicateMessageReceiveEventIsIgnored() throws Exception {
        when(codeRagAnswerEvidenceService.retrieve(TEST_REPO_ID, "where is queue"))
                .thenReturn(CodeAnswerEvidenceResult.builder().selectedEvidence(List.of()).build());
        String body = textEvent("om_duplicate", "/ask-code hmdp where is queue");

        mockMvc.perform(post("/api/feishu/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(post("/api/feishu/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(codeRagAnswerEvidenceService).retrieve(eq(TEST_REPO_ID), eq("where is queue"));
    }

    @Test
    void messageReceiveNonHelpTextEventDoesNotSendMessage() throws Exception {
        mockMvc.perform(post("/api/feishu/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textEvent("om_test", "hello")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(messageClient, never()).sendText(anyString(), anyString());
    }

    @Test
    void messageReceiveNonTextEventReturnsOkCode() throws Exception {
        mockMvc.perform(post("/api/feishu/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "schema": "2.0",
                                  "header": {
                                    "event_type": "im.message.receive_v1"
                                  },
                                  "event": {
                                    "sender": {
                                      "sender_id": {
                                        "open_id": "ou_test"
                                      }
                                    },
                                    "message": {
                                      "message_id": "om_image",
                                      "chat_id": "oc_test",
                                      "chat_type": "p2p",
                                      "message_type": "image",
                                      "content": "{\\"image_key\\":\\"img_test\\"}"
                                    }
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void messageReceiveInvalidTextContentReturnsOkCode() throws Exception {
        mockMvc.perform(post("/api/feishu/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "schema": "2.0",
                                  "header": {
                                    "event_type": "im.message.receive_v1"
                                  },
                                  "event": {
                                    "message": {
                                      "message_id": "om_invalid",
                                      "chat_id": "oc_test",
                                      "chat_type": "p2p",
                                      "message_type": "text",
                                      "content": "not-json"
                                    }
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(messageClient, never()).sendText(anyString(), anyString());
    }

    @Test
    void messageSendFailureDoesNotAffectEventResponse() throws Exception {
        doThrow(new RuntimeException("send failed")).when(messageClient).sendText(anyString(), anyString());

        mockMvc.perform(post("/api/feishu/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textEvent("om_help_failed", "/help")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    private String textEvent(String messageId, String text) {
        return """
                {
                  "schema": "2.0",
                  "header": {
                    "event_type": "im.message.receive_v1"
                  },
                  "event": {
                    "sender": {
                      "sender_id": {
                        "open_id": "ou_test"
                      }
                    },
                    "message": {
                      "message_id": "%s",
                      "chat_id": "oc_test",
                      "chat_type": "p2p",
                      "message_type": "text",
                      "content": "{\\"text\\":\\"%s\\"}"
                    }
                  }
                }
                """.formatted(messageId, text);
    }
}
