package com.kama.jchatmind.integration.feishu;

import com.kama.jchatmind.agent.JChatMind;
import com.kama.jchatmind.agent.JChatMindFactory;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.request.CreateChatMessageRequest;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.service.AgentTaskLogService;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class FeishuAgentRunAdapter {

    private static final int RECENT_MESSAGE_LIMIT = 30;
    private static final int FEISHU_MAX_AGENT_LOOP_STEPS = 12;
    private static final String STEP_LIMIT_FALLBACK_ANSWER = """
            Agent 已达到飞书轻量运行步数上限。
            这通常表示模型仍在继续检索局部细节，但当前对话场景会优先停止以避免长时间无回复。
            请换用更聚焦的问题，或使用 /ask-code 获取更直接的代码证据。
            """;

    private final JChatMindFactory jChatMindFactory;
    private final ChatMessageFacadeService chatMessageFacadeService;
    private final FeishuAgentSessionBindingService sessionBindingService;
    private final FeishuProperties feishuProperties;

    public AgentRunResult run(String agentId, String chatId, String chatType, String senderOpenId, String question) {
        if (!StringUtils.hasText(agentId)) {
            throw new IllegalArgumentException("Feishu default agent id is not configured");
        }
        String sessionId = sessionBindingService.getOrCreateActiveSession(chatId, chatType, senderOpenId, agentId);
        CreateChatMessageResponse userMessage = chatMessageFacadeService.agentCreateChatMessage(
                CreateChatMessageRequest.builder()
                        .agentId(agentId)
                        .sessionId(sessionId)
                        .role(ChatMessageDTO.RoleType.USER)
                        .content(withFeishuContext(question))
                        .build());

        JChatMind agent = jChatMindFactory.create(agentId, sessionId, userMessage.getChatMessageId());
        agent.setMaxLoopSteps(FEISHU_MAX_AGENT_LOOP_STEPS);
        agent.run();

        if (AgentTaskLogService.FINISH_REASON_MAX_STEPS_REACHED.equals(agent.getFinishReason())) {
            return new AgentRunResult(sessionId, userMessage.getChatMessageId(), STEP_LIMIT_FALLBACK_ANSWER);
        }
        String answer = loadLatestAssistantAnswer(sessionId);
        return new AgentRunResult(sessionId, userMessage.getChatMessageId(), answer);
    }

    String stableChatKey(String chatId) {
        String source = "feishu:" + (StringUtils.hasText(chatId) ? chatId : "unknown");
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
    }

    String withFeishuContext(String question) {
        String hmdpRepoId = feishuProperties.getRepoAliases().get("hmdp");
        if (!StringUtils.hasText(hmdpRepoId)) {
            return question;
        }
        return question + "\n\n"
                + "Feishu context:\n"
                + "- default code repository alias: hmdp / 黑马点评\n"
                + "- default code repository repoId: " + hmdpRepoId + "\n"
                + "- When the question mentions 黑马点评, hmdp, 秒杀, 优惠券, 订单, 库存, "
                + "use this repoId for searchProjectCode.";
    }

    private String loadLatestAssistantAnswer(String sessionId) {
        List<ChatMessageDTO> recentMessages =
                chatMessageFacadeService.getChatMessagesBySessionIdRecently(sessionId, RECENT_MESSAGE_LIMIT);
        return recentMessages.stream()
                .filter(this::isVisibleAssistantMessage)
                .max(Comparator.comparing(ChatMessageDTO::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(ChatMessageDTO::getContent)
                .filter(StringUtils::hasText)
                .orElse("Agent run completed, but no final assistant answer was saved.");
    }

    private boolean isVisibleAssistantMessage(ChatMessageDTO message) {
        if (message == null || message.getRole() != ChatMessageDTO.RoleType.ASSISTANT) {
            return false;
        }
        return message.getMetadata() == null
                || message.getMetadata().getToolCalls() == null
                || message.getMetadata().getToolCalls().isEmpty();
    }

    public record AgentRunResult(String sessionId, String userMessageId, String answer) {
    }
}
