package com.kama.jchatmind.agent;

import com.kama.jchatmind.config.FinalSynthesisProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FinalSynthesisMaturityTest {

    @Test
    void i1ToI5StructuredProjectionPreservesTranscriptAndCompilesEvidenceAsUntrustedUserData() {
        List<Message> transcript = new ArrayList<>();
        transcript.add(new SystemMessage("agent system"));
        transcript.add(new UserMessage("old question"));
        transcript.add(AssistantMessage.builder().content("old answer").toolCalls(List.of()).build());
        transcript.add(new UserMessage("current question"));
        transcript.add(toolCalls("call-a", "call-b"));
        transcript.add(toolResponses(response("call-b", "searchProjectCode", "B <untrusted>"),
                response("call-a", "databaseQuery", "A")));
        List<Message> originalReferences = List.copyOf(transcript);

        FinalSynthesisRequest request = new FinalSynthesisRequestFactory().create(transcript);
        List<Message> compiled = new FinalContextCompiler().compile(request);

        assertThat(transcript).hasSameSizeAs(originalReferences);
        for (int index = 0; index < transcript.size(); index++) {
            assertThat(transcript.get(index)).isSameAs(originalReferences.get(index));
        }
        assertThat(request.originalUserQuestion()).isEqualTo("current question");
        assertThat(request.evidenceBatches()).singleElement().satisfies(batch -> {
            assertThat(batch.evidence()).extracting(FinalEvidence::content).containsExactly("A", "B <untrusted>");
            assertThat(batch.evidence()).extracting(FinalEvidence::toolName)
                    .containsExactly("databaseQuery", "searchProjectCode");
        });
        assertThat(compiled).noneMatch(ToolResponseMessage.class::isInstance);
        assertThat(compiled.stream().filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast))
                .allMatch(message -> !message.getText().contains("B <untrusted>"));
        assertThat(compiled.get(compiled.size() - 1)).isInstanceOf(UserMessage.class);
        assertThat(compiled.get(compiled.size() - 1).getText())
                .contains("current question", "A", "B &lt;untrusted&gt;", "Now answer the original user question")
                .doesNotContain("[FINAL_EVIDENCE_BATCH]");
    }

    @Test
    void i2AndI12IncompleteToolProtocolStillFailsClosed() {
        List<Message> transcript = List.of(
                new UserMessage("question"),
                toolCalls("call-a"));

        assertThatThrownBy(() -> new FinalSynthesisRequestFactory().create(transcript))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incomplete or orphaned tool protocol");
    }

    @Test
    void originalQuestionRemainsExplicitWhenProtocolAwareWindowEvictsItsMessage() {
        List<Message> retainedTranscript = List.of(
                new SystemMessage("agent system"),
                toolCalls("call-a"),
                toolResponses(response("call-a", "searchProjectCode", "evidence")));

        FinalSynthesisRequest request = new FinalSynthesisRequestFactory().create(
                retainedTranscript, "current question evicted from the bounded window");
        List<Message> compiled = new FinalContextCompiler().compile(request);

        assertThat(request.originalUserQuestion())
                .isEqualTo("current question evicted from the bounded window");
        assertThat(compiled.get(compiled.size() - 1).getText())
                .contains("current question evicted from the bounded window", "evidence");
    }

    @Test
    void i4CompilerAlwaysEndsWithExplicitAnswerInstructionAndNeverAssistantEvidence() {
        FinalSynthesisRequest request = new FinalSynthesisRequest(
                "explain project",
                List.of(new FinalConversationMessage(FinalConversationMessage.Role.SYSTEM, "summary")),
                List.of(new FinalEvidenceBatch(1, List.of(
                        new FinalEvidence("evidence-1-1", "call-1", "searchProjectCode",
                                "application.yml and seckill.lua", null)))),
                "Answer accurately");

        List<Message> compiled = new FinalContextCompiler().compile(request);

        assertThat(compiled.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(compiled.get(compiled.size() - 1)).isInstanceOf(UserMessage.class);
        assertThat(compiled.get(compiled.size() - 1).getText())
                .endsWith("Do not reproduce any evidence tags, internal markers, diagnostics, or tool protocol.");
        assertThat(compiled.stream().filter(AssistantMessage.class::isInstance)
                .map(Message::getText)).doesNotContain("application.yml and seckill.lua");
    }

    @Test
    void deterministicBudgetDropsOldConversationBeforeAtomicEvidence() {
        FinalSynthesisRequest request = new FinalSynthesisRequest(
                "question",
                List.of(
                        new FinalConversationMessage(FinalConversationMessage.Role.USER, "old-" + "x".repeat(2000)),
                        new FinalConversationMessage(FinalConversationMessage.Role.ASSISTANT, "old answer")),
                List.of(new FinalEvidenceBatch(1, List.of(
                        new FinalEvidence("evidence-1-1", "call-1", "tool", "must remain", null)))),
                "answer");

        List<Message> compiled = new FinalContextCompiler(1200, 1).compile(request);

        assertThat(compiled).extracting(Message::getText).noneMatch(text -> text.startsWith("old-"));
        assertThat(compiled.get(compiled.size() - 1).getText()).contains("must remain", "question");
    }

    @Test
    void configuredFinalBudgetAcceptsThePreviouslyFailingEvidenceVolume() {
        String evidence = "x".repeat(45_462);
        FinalSynthesisRequest request = new FinalSynthesisRequest(
                "introduce the complete flow",
                List.of(new FinalConversationMessage(FinalConversationMessage.Role.SYSTEM, "summary")),
                List.of(new FinalEvidenceBatch(1, List.of(
                        new FinalEvidence("evidence-1-1", "call-1", "searchProjectCode", evidence, null)))),
                "answer accurately");

        assertThatThrownBy(() -> new FinalContextCompiler(12_000, 3).compile(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds token budget");

        FinalSynthesisProperties properties = new FinalSynthesisProperties();
        properties.setMaxInputTokens(64_000);
        properties.setCharsPerToken(3);
        List<Message> compiled = new FinalContextCompiler(properties).compile(request);

        assertThat(compiled.get(compiled.size() - 1).getText())
                .contains("introduce the complete flow", evidence);
    }

    @Test
    void i6ToI8ValidatorRejectsPseudoBatchAndSplitMarkerAfterAggregation() {
        FinalOutputValidator validator = new FinalOutputValidator();
        String splitChunksAggregated = "[FINAL_EVI" + "DENCE_BATCH]\nBatch: 3\nContent";

        FinalOutputValidator.ValidationResult result = validator.validate(
                splitChunksAggregated, false, "STOP");

        assertThat(result.valid()).isFalse();
        assertThat(result.violationCodes()).contains(
                FinalOutputValidator.ViolationCode.INTERNAL_MARKER_LEAKAGE);
    }

    @Test
    void validatorAllowsNormalTechnicalAnswerWithFileNames() {
        FinalOutputValidator.ValidationResult result = new FinalOutputValidator().validate(
                "The application.yml configures the service and seckill.lua performs the atomic reservation.",
                false, "stop");

        assertThat(result.valid()).isTrue();
        assertThat(result.violationCodes()).isEmpty();
    }

    @Test
    void validatorAllowsOrdinaryBatchAndStandaloneLegacyLabels() {
        FinalOutputValidator validator = new FinalOutputValidator();

        assertThat(validator.validate("Batch: 3 \u8868\u793a\u7b2c\u4e09\u4e2a\u6279\u6b21\u3002", false, "STOP").valid()).isTrue();
        assertThat(validator.validate("  Batch: 3 \u8868\u793a\u7b2c\u4e09\u4e2a\u6279\u6b21\u3002", false, "STOP").valid()).isTrue();
        assertThat(validator.validate("\u8bad\u7ec3\u65f6\u5c06 batch size \u8bbe\u7f6e\u4e3a 32\u3002", false, "STOP").valid()).isTrue();
        assertThat(validator.validate("Source-Tool: \u662f\u672c\u6587\u5b9a\u4e49\u7684\u4e00\u4e2a\u5b57\u6bb5\u540d\u79f0\u3002", false, "STOP").valid()).isTrue();
        assertThat(validator.validate("Content-Characters: \u7528\u6765\u8868\u793a\u5b57\u7b26\u6570\u91cf\u3002", false, "STOP").valid()).isTrue();
        assertThat(validator.validate("RabbitMQ \u6d88\u8d39\u8005\u6bcf\u6279\u5904\u7406 100 \u6761\u6d88\u606f\u3002", false, "STOP").valid()).isTrue();
        assertThat(validator.validate("application.yml \u548c seckill.lua \u5206\u522b\u7528\u4e8e\u914d\u7f6e\u548c\u79d2\u6740\u903b\u8f91\u3002", false, "STOP").valid()).isTrue();
    }

    @Test
    void validatorStillRejectsStrongMarkersAndHighPrecisionLegacyCombination() {
        FinalOutputValidator validator = new FinalOutputValidator();

        assertThat(validator.validate("[FINAL_EVIDENCE_BATCH]\nBatch: 3\n...", false, "STOP").valid())
                .isFalse();
        assertThat(validator.validate("<final_evidence_data>\n...\n</final_evidence_data>", false, "STOP").valid())
                .isFalse();
        FinalOutputValidator.ValidationResult legacy = validator.validate(
                "Batch: 3\nEvidence-Count: 1\nSource-Tool: searchProjectCode\n"
                        + "Content-Characters: 42\nContent: evidence",
                false, "STOP");
        assertThat(legacy.valid()).isFalse();
        assertThat(legacy.violationCodes())
                .contains(FinalOutputValidator.ViolationCode.EVIDENCE_BATCH_CONTINUATION);
    }

    @Test
    void validatorRejectsEmptyToolCallBadFinishAndInternalDiagnostics() {
        FinalOutputValidator validator = new FinalOutputValidator();

        assertThat(validator.validate("", false, "STOP").violationCodes())
                .contains(FinalOutputValidator.ViolationCode.EMPTY_TEXT);
        assertThat(validator.validate("answer", true, "STOP").violationCodes())
                .contains(FinalOutputValidator.ViolationCode.UNEXPECTED_TOOL_CALL);
        assertThat(validator.validate("answer", false, "length").violationCodes())
                .contains(FinalOutputValidator.ViolationCode.INVALID_FINISH_REASON);
        assertThat(validator.validate("returnedEvidenceCount=1\nnewEvidenceCount=1", false, "STOP")
                .violationCodes()).contains(FinalOutputValidator.ViolationCode.INTERNAL_PROTOCOL_OUTPUT);
    }

    private AssistantMessage toolCalls(String... ids) {
        return AssistantMessage.builder().content("")
                .toolCalls(java.util.Arrays.stream(ids)
                        .map(id -> new AssistantMessage.ToolCall(id, "function", "tool-" + id, "{}"))
                        .toList())
                .build();
    }

    private ToolResponseMessage toolResponses(ToolResponseMessage.ToolResponse... responses) {
        return ToolResponseMessage.builder().responses(List.of(responses)).build();
    }

    private ToolResponseMessage.ToolResponse response(String id, String name, String content) {
        return new ToolResponseMessage.ToolResponse(id, name, content);
    }
}
