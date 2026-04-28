package com.kama.jchatmind.agent.examples;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JChatMindV1 测试类
 * 测试基础聊天功能
 */
@SpringBootTest
public class JChatMindV1Test {

    @Autowired
    @Qualifier("deepseek-chat")
    private ChatClient chatClient;

    @Test
    public void testBasicChat() {
        // 创建 V1 实例
        JChatMindV1 agent = new JChatMindV1(
                "test-agent-v1",
                "测试 Agent V1",
                "你是一个友好的助手。",
                chatClient,
                20,
                "test-session-v1"
        );

        // 测试简单对话
        String userInput = "你好，请介绍一下你自己。";
        String response = agent.chat(userInput);

        // 验证回复不为空
        assertNotNull(response);
        assertTrue(response.length() > 0);

        System.out.println("用户输入: " + userInput);
        System.out.println("AI 回复: " + response);
        System.out.println("对话历史长度: " + agent.getConversationHistory().size());
    }

    @Test
    public void testMultiTurnConversation() {
        // 创建 V1 实例
        JChatMindV1 agent = new JChatMindV1(
                "test-agent-v1",
                "测试 Agent V1",
                "",
                chatClient,
                20,
                "test-session-v1-multi"
        );

        // 第一轮对话
        String response1 = agent.chat("我的名字叫做张三");
        assertNotNull(response1);
        System.out.println("第一轮 - [用户]: 我的名字叫做张三？");
        System.out.println("第一轮 - [AI]: " + response1);

        // 第二轮对话（测试上下文记忆）
        String response2 = agent.chat("我的名字叫做什么？");
        assertNotNull(response2);
        System.out.println("第二轮 - [用户]: 我的名字叫做什么？");
        System.out.println("第二轮 - [AI]: " + response2);

        // 验证对话历史包含多轮对话
        assertTrue(agent.getConversationHistory().size() >= 4); // 至少包含：系统消息 + 用户消息1 + AI回复1 + 用户消息2 + AI回复2
    }

    @Test
    public void testResetConversation() {
        // 创建 V1 实例
        JChatMindV1 agent = new JChatMindV1(
                "test-agent-v1",
                "测试 Agent V1",
                "你是一个助手。",
                chatClient,
                20,
                "test-session-v1-reset"
        );

        // 进行对话
        agent.chat("你好");
        int historySizeBeforeReset = agent.getConversationHistory().size();

        // 重置对话
        agent.reset();

        // 验证对话历史已重置（只保留系统消息）
        int historySizeAfterReset = agent.getConversationHistory().size();
        assertTrue(historySizeAfterReset < historySizeBeforeReset);
        assertTrue(historySizeAfterReset <= 1); // 只有系统消息

        System.out.println("重置前历史长度: " + historySizeBeforeReset);
        System.out.println("重置后历史长度: " + historySizeAfterReset);
    }
}

