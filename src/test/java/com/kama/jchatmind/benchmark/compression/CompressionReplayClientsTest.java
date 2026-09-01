package com.kama.jchatmind.benchmark.compression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CompressionReplayClientsTest {
    private static final String BASE_URL = "https://deepseek.example/v1";
    private static final String API_KEY = "benchmark-test-key-never-persist";
    private static final String MODEL = "deepseek-v4-flash";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesThinkingEnabledAtWireLevel() throws Exception {
        String json = CompressionReplayClients.deepSeekRequestJson(
                objectMapper, MODEL, CompressionReplayClients.ThinkingMode.ENABLED, null, "same prompt");
        JsonNode root = objectMapper.readTree(json);

        assertEquals(MODEL, root.path("model").asText());
        assertEquals("enabled", root.path("thinking").path("type").asText());
        assertEquals("same prompt", root.path("messages").get(0).path("content").asText());
        assertFalse(root.has("temperature"));
        assertFalse(root.has("top_p"));
        assertFalse(root.has("max_tokens"));
        assertTrue(CompressionReplayClients.verifyDeepSeekWireContract(
                objectMapper, MODEL, CompressionReplayClients.ThinkingMode.ENABLED, null));
    }

    @Test
    void serializesThinkingDisabledAtWireLevel() throws Exception {
        String json = CompressionReplayClients.deepSeekRequestJson(
                objectMapper, MODEL, CompressionReplayClients.ThinkingMode.DISABLED, null, "same prompt");
        JsonNode root = objectMapper.readTree(json);

        assertEquals(MODEL, root.path("model").asText());
        assertEquals("disabled", root.path("thinking").path("type").asText());
        assertFalse(root.has("max_tokens"));
        assertTrue(CompressionReplayClients.verifyDeepSeekWireContract(
                objectMapper, MODEL, CompressionReplayClients.ThinkingMode.DISABLED, null));
    }

    @Test
    void capturedRequestContainsExplicitThinkingAndResponseReasoningIsMeasured() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CompressionReplayClients.Client client = CompressionReplayClients.deepSeek(
                builder, objectMapper, BASE_URL, API_KEY, MODEL,
                CompressionReplayClients.ThinkingMode.ENABLED, null, 3);
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "model":"deepseek-v4-flash",
                          "messages":[{"role":"user","content":"frozen prompt"}],
                          "stream":false,
                          "thinking":{"type":"enabled"}
                        }
                        """, true))
                .andRespond(withSuccess("""
                        {
                          "choices":[{
                            "message":{"content":"Current Task Continuation State Delta","reasoning_content":"reason"},
                            "finish_reason":"stop"
                          }],
                          "usage":{"prompt_tokens":11,"completion_tokens":7,"total_tokens":18}
                        }
                        """, MediaType.APPLICATION_JSON));

        String output = client.summarize("measurement-model", "frozen prompt");

        assertEquals("Current Task Continuation State Delta", output);
        CompressionReplayClients.Invocation invocation = client.invocations().get(0);
        assertEquals(11, invocation.actualInputTokens());
        assertEquals(7, invocation.actualOutputTokens());
        assertTrue(invocation.reasoningContentPresent());
        assertEquals(6, invocation.reasoningChars());
        assertFalse(invocation.promptSha256().contains(API_KEY));
        server.verify();
    }
}
