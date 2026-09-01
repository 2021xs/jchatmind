package com.kama.jchatmind.agent;

import com.kama.jchatmind.service.ConversationContextCompressor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FinalSynthesisRequestFactoryTest {

    private final FinalSynthesisRequestFactory factory = new FinalSynthesisRequestFactory();

    @Test
    void noToolRetainsEligibleHistoryAndCurrentUser() {
        FinalSynthesisRequest shadow = factory.create(List.of(
                new SystemMessage("agent system"),
                new UserMessage("historical user"),
                assistant("historical final"),
                new UserMessage("current raw user")), "current raw user");

        assertThat(shadow.originalUserQuestion()).isEqualTo("current raw user");
        assertThat(conversationContents(shadow)).containsExactly(
                "agent system", "historical user", "historical final");
        assertThat(shadow.evidenceBatches()).isEmpty();
    }

    @Test
    void smallToolModelViewIsRetainedWithoutTranscript() {
        FinalSynthesisRequest shadow = factory.create(List.of(
                new UserMessage("question"),
                toolCalls("call-small", "databaseQuery"),
                toolResponses("call-small", "databaseQuery", "small projected result")), "question");

        assertThat(evidenceContents(shadow)).containsExactly("small projected result");
    }

    @Test
    void structuredSearchModelViewKeepsStableLocator() {
        String projected = """
                Selected code evidence:
                repoId: repo-1
                chunkId: chunk-1
                file: VoucherOrderController.java
                symbol: VoucherOrderController#seckillVoucher
                """;

        FinalSynthesisRequest shadow = factory.create(List.of(
                new UserMessage("where is the endpoint"),
                toolCalls("call-search", "searchProjectCode"),
                toolResponses("call-search", "searchProjectCode", projected)), "where is the endpoint");

        assertThat(evidenceContents(shadow).get(0))
                .contains("repoId: repo-1", "chunkId: chunk-1", "VoucherOrderController#seckillVoucher");
    }

    @Test
    void exactChunkDetailRemainsAvailableFromActiveRawGroup() {
        String exactChunk = "Exact code chunk:\nrepoId: repo-1\nchunkId: chunk-lua\nreturn 3";

        FinalSynthesisRequest shadow = factory.create(List.of(
                new UserMessage("list exact return codes"),
                toolCalls("call-chunk", "getCodeChunk"),
                toolResponses("call-chunk", "getCodeChunk", exactChunk)), "list exact return codes");

        assertThat(evidenceContents(shadow)).containsExactly(exactChunk);
    }

    @Test
    void coveredGroupIsRepresentedByStateWithoutRawDuplication() {
        String state = "Goal:\n- explain flow\nKnown:\n- Lua return 3 means missing stock key\n"
                + "Refs:\n- repoId=repo-1 chunkId=chunk-lua";
        String stateMessage = ConversationContextCompressor.continuationStateMessageContent(state);

        FinalSynthesisRequest shadow = factory.create(List.of(
                new SystemMessage(stateMessage),
                new UserMessage("explain flow")), "explain flow");

        assertThat(conversationContents(shadow)).contains(stateMessage);
        assertThat(shadow.evidenceBatches()).isEmpty();
    }

    @Test
    void uncoveredGroupIsRetainedAlongsideAcceptedState() {
        String state = ConversationContextCompressor.continuationStateMessageContent(
                "Goal:\n- explain flow\nKnown:\n- producer sends the order");

        FinalSynthesisRequest shadow = factory.create(List.of(
                new SystemMessage(state),
                new UserMessage("explain flow"),
                toolCalls("call-uncovered", "getCodeChunk"),
                toolResponses("call-uncovered", "getCodeChunk", "consumer exact detail")), "explain flow");

        assertThat(conversationContents(shadow)).contains(state);
        assertThat(evidenceContents(shadow)).containsExactly("consumer exact detail");
    }

    @Test
    void crossTaskUsesHistoricalUserFinalProjectionWithoutRawToolLeakage() {
        FinalSynthesisRequest shadow = factory.create(List.of(
                new UserMessage("historical question"),
                assistant("historical final answer"),
                new UserMessage("current question")), "current question");

        assertThat(conversationContents(shadow))
                .containsExactly("historical question", "historical final answer")
                .doesNotContain("OLD_TOOL_SECRET");
        assertThat(shadow.evidenceBatches()).isEmpty();
    }

    @Test
    void managedInputIsTheOnlyFinalEvidenceSource() {
        List<Message> managed = List.of(
                new UserMessage("question"),
                toolCalls("call-managed", "searchProjectCode"),
                toolResponses("call-managed", "searchProjectCode", "managed evidence"));
        FinalSynthesisRequest before = factory.create(managed, "question");
        FinalSynthesisRequest after = factory.create(managed, "question");

        assertThat(after).isEqualTo(before);
        assertThat(evidenceContents(after)).containsExactly("managed evidence");
    }

    @Test
    void protocolIntegrityFailsClosedForOrphanToolMessage() {
        assertThatThrownBy(() -> factory.create(List.of(
                new UserMessage("question"),
                toolResponses("orphan", "searchProjectCode", "evidence")), "question"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incomplete or orphaned tool protocol");
    }

    @Test
    void currentUserIsPreservedExactlyAsOriginalQuestion() {
        String raw = "Current user with exact value 36000 and spacing  ";

        FinalSynthesisRequest shadow = factory.create(
                List.of(new UserMessage(raw)), raw);

        assertThat(shadow.originalUserQuestion()).isEqualTo(raw);
        List<Message> compiled = new FinalContextCompiler().compile(shadow);
        assertThat(compiled.get(compiled.size() - 1).getText()).contains(raw);
    }

    private List<String> conversationContents(FinalSynthesisRequest request) {
        return request.conversationContext().stream().map(FinalConversationMessage::content).toList();
    }

    private List<String> evidenceContents(FinalSynthesisRequest request) {
        return request.evidenceBatches().stream()
                .flatMap(batch -> batch.evidence().stream())
                .map(FinalEvidence::content)
                .toList();
    }

    private AssistantMessage assistant(String content) {
        return AssistantMessage.builder().content(content).toolCalls(List.of()).build();
    }

    private AssistantMessage toolCalls(String id, String toolName) {
        return AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall(id, "function", toolName, "{}"))).build();
    }

    private ToolResponseMessage toolResponses(String id, String toolName, String content) {
        return ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse(id, toolName, content))).build();
    }
}
