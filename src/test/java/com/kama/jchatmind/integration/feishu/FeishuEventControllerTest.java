package com.kama.jchatmind.integration.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FeishuEventControllerTest {

    private MockMvc mockMvc;
    private FeishuMessageClient messageClient;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        FeishuProperties properties = new FeishuProperties();
        properties.setVerificationToken("test-token");
        messageClient = mock(FeishuMessageClient.class);
        FeishuBotService botService = new FeishuBotService(new FeishuCommandParser(), messageClient);
        FeishuMessageEventHandler messageEventHandler = new FeishuMessageEventHandler(objectMapper, botService);
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
                        .content("""
                                {
                                  "schema": "2.0",
                                  "header": {
                                    "event_type": "im.message.receive_v1"
                                  },
                                  "event": {
                                    "message": {
                                      "message_id": "om_help",
                                      "chat_id": "oc_test",
                                      "chat_type": "p2p",
                                      "message_type": "text",
                                      "content": "{\\"text\\":\\"/help\\"}"
                                    }
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(messageClient).sendText(eq("oc_test"), eq(FeishuBotService.HELP_TEXT));
    }

    @Test
    void messageReceiveNonHelpTextEventDoesNotSendMessage() throws Exception {
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
                                      "message_id": "om_test",
                                      "chat_id": "oc_test",
                                      "chat_type": "p2p",
                                      "message_type": "text",
                                      "content": "{\\"text\\":\\"hello\\"}"
                                    }
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(messageClient, never()).sendText(anyString(), anyString());
    }

    @Test
    void messageReceiveTextEventReturnsOkCode() throws Exception {
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
                                      "message_id": "om_test",
                                      "chat_id": "oc_test",
                                      "chat_type": "p2p",
                                      "message_type": "text",
                                      "content": "{\\"text\\":\\"hello\\"}"
                                    }
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
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
                        .content("""
                                {
                                  "schema": "2.0",
                                  "header": {
                                    "event_type": "im.message.receive_v1"
                                  },
                                  "event": {
                                    "message": {
                                      "message_id": "om_help",
                                      "chat_id": "oc_test",
                                      "chat_type": "p2p",
                                      "message_type": "text",
                                      "content": "{\\"text\\":\\"/help\\"}"
                                    }
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
}
