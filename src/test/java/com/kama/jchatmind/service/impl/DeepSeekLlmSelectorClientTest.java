package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.model.dto.SelectorModelResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DeepSeekLlmSelectorClientTest {
    private static final String BASE_URL = "https://deepseek.example/v1";
    private static final String API_KEY = "test-api-key";
    private static final String PROVIDER_MODEL = "deepseek-v4-flash";

    private MockRestServiceServer server;
    private DeepSeekLlmSelectorClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new DeepSeekLlmSelectorClient(
                builder, new ObjectMapper(), BASE_URL, API_KEY, PROVIDER_MODEL);
    }

    @Test
    void sendsThinkingDisabledContractAndMapsResponse() {
        String prompt = "existing selector prompt";
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.model").value(PROVIDER_MODEL))
                .andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].content").value(prompt))
                .andExpect(jsonPath("$.stream").value(false))
                .andExpect(jsonPath("$.thinking.type").value("disabled"))
                .andExpect(jsonPath("$.max_tokens").doesNotExist())
                .andExpect(jsonPath("$.temperature").doesNotExist())
                .andExpect(jsonPath("$.top_p").doesNotExist())
                .andRespond(withSuccess("""
                        {
                          "choices": [
                            {
                              "message": {
                                "content": "{\\\"selectedCandidateIds\\\":[\\\"C01\\\"]}",
                                "reasoning_content": "abc"
                              },
                              "finish_reason": "stop"
                            }
                          ],
                          "usage": {
                            "prompt_tokens": 100,
                            "completion_tokens": 20,
                            "total_tokens": 120
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        SelectorModelResponse response = client.call(prompt);

        assertEquals("{\"selectedCandidateIds\":[\"C01\"]}", response.getContent());
        assertTrue(response.getReasoningContentPresent());
        assertEquals(3, response.getReasoningContentChars());
        assertEquals(100, response.getPromptTokens());
        assertEquals(20, response.getCompletionTokens());
        assertEquals(120, response.getTotalTokens());
        assertEquals("stop", response.getFinishReason());
        server.verify();
    }

    @Test
    void mapsMissingReasoningContentToAbsentAndZeroChars() {
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withSuccess("""
                        {
                          "choices": [
                            {
                              "message": {
                                "content": "{\\\"selectedCandidateIds\\\":[]}"
                              },
                              "finish_reason": "stop"
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        SelectorModelResponse response = client.call("prompt");

        assertFalse(response.getReasoningContentPresent());
        assertEquals(0, response.getReasoningContentChars());
        assertEquals(null, response.getPromptTokens());
        assertEquals(null, response.getCompletionTokens());
        assertEquals(null, response.getTotalTokens());
        server.verify();
    }

    @Test
    void propagatesClientHttpErrorsWithoutFallback() {
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"message\":\"bad request\"}}"));

        assertThrows(HttpClientErrorException.class, () -> client.call("prompt"));
        server.verify();
    }

    @Test
    void propagatesServerHttpErrorsWithoutRetryOrFallback() {
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"message\":\"server error\"}}"));

        assertThrows(HttpServerErrorException.class, () -> client.call("prompt"));
        server.verify();
    }

    @Test
    void rejectsResponseWithNoChoices() {
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> client.call("prompt"));

        assertTrue(error.getMessage().contains("no choices"));
        server.verify();
    }
}
