package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.AgentEventPublisher;
import com.kama.jchatmind.agent.AgentTaskRuntimeRegistry;
import com.kama.jchatmind.agent.JChatMind;
import com.kama.jchatmind.agent.JChatMindFactory;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.mapper.ChatSessionMapper;
import com.kama.jchatmind.mapper.CodeRepositoryMapper;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.ChatSession;
import com.kama.jchatmind.model.entity.CodeRepository;
import com.kama.jchatmind.model.entity.AgentTask;
import com.kama.jchatmind.model.request.CreateChatMessageRequest;
import com.kama.jchatmind.model.request.WebConsoleChatSendRequest;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.model.response.GetWebConsoleCapabilitiesResponse;
import com.kama.jchatmind.model.response.WebConsoleChatSendResponse;
import com.kama.jchatmind.model.vo.WebConsoleCapabilityVO;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.AgentTaskLifecycleService;
import com.kama.jchatmind.service.AgentTaskLogService;
import com.kama.jchatmind.service.WebConsoleCapabilityService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebConsoleChatServiceImplTest {

    @Test
    void sendRejectsRepositoryMismatchBeforeUserMessageOrTaskReservation() {
        ChatSessionMapper chatSessionMapper = mock(ChatSessionMapper.class);
        CodeRepositoryMapper codeRepositoryMapper = mock(CodeRepositoryMapper.class);
        ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        JChatMindFactory jChatMindFactory = mock(JChatMindFactory.class);
        ChatClientRegistry chatClientRegistry = new ChatClientRegistry(
                Map.of("gpt-compatible-chat", mock(ChatClient.class, RETURNS_DEEP_STUBS)));
        WebConsoleCapabilityService capabilityService = mock(WebConsoleCapabilityService.class);

        when(chatSessionMapper.selectById("session-bound")).thenReturn(ChatSession.builder()
                .id("session-bound")
                .agentId("agent-1")
                .metadata("{\"channel\":\"WEB_CONSOLE\",\"repoId\":\"repo-a\"}")
                .build());
        WebConsoleChatServiceImpl service = new WebConsoleChatServiceImpl(
                chatSessionMapper, codeRepositoryMapper, chatMessageFacadeService, jChatMindFactory,
                chatClientRegistry, new ObjectMapper(), mock(AgentEventPublisher.class), capabilityService,
                Runnable::run);
        WebConsoleChatSendRequest request = new WebConsoleChatSendRequest();
        request.setConversationId("session-bound");
        request.setRepoId("repo-b");
        request.setContent("question");

        assertThatThrownBy(() -> service.send(request))
                .isInstanceOf(com.kama.jchatmind.exception.BizException.class)
                .hasMessageContaining("SESSION_REPOSITORY_MISMATCH");
        org.mockito.Mockito.verifyNoInteractions(chatMessageFacadeService, codeRepositoryMapper);
    }

    @Test
    void sendRejectsLegacyUnboundSessionWithoutGuessingRepository() {
        ChatSessionMapper chatSessionMapper = mock(ChatSessionMapper.class);
        ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        ChatClientRegistry chatClientRegistry = new ChatClientRegistry(
                Map.of("gpt-compatible-chat", mock(ChatClient.class, RETURNS_DEEP_STUBS)));
        when(chatSessionMapper.selectById("legacy-session")).thenReturn(ChatSession.builder()
                .id("legacy-session").agentId("agent-1")
                .metadata("{\"channel\":\"WEB_CONSOLE\"}").build());
        WebConsoleChatServiceImpl service = new WebConsoleChatServiceImpl(
                chatSessionMapper, mock(CodeRepositoryMapper.class), chatMessageFacadeService,
                mock(JChatMindFactory.class), chatClientRegistry, new ObjectMapper(),
                mock(AgentEventPublisher.class), mock(WebConsoleCapabilityService.class), Runnable::run);
        WebConsoleChatSendRequest request = new WebConsoleChatSendRequest();
        request.setConversationId("legacy-session");
        request.setRepoId("repo-a");
        request.setContent("question");

        assertThatThrownBy(() -> service.send(request))
                .isInstanceOf(com.kama.jchatmind.exception.BizException.class)
                .hasMessageContaining("SESSION_REPOSITORY_UNBOUND");
        org.mockito.Mockito.verifyNoInteractions(chatMessageFacadeService);
    }

    @Test
    void sendCreatesPlainUserMessageAndRunsExistingAgentWithWebConsoleRepoContext() {
        ChatSessionMapper chatSessionMapper = mock(ChatSessionMapper.class);
        CodeRepositoryMapper codeRepositoryMapper = mock(CodeRepositoryMapper.class);
        ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        JChatMindFactory jChatMindFactory = mock(JChatMindFactory.class);
        ChatClientRegistry chatClientRegistry = new ChatClientRegistry(
                Map.of("gpt-compatible-chat", mock(ChatClient.class, RETURNS_DEEP_STUBS)));
        AgentEventPublisher eventPublisher = mock(AgentEventPublisher.class);
        WebConsoleCapabilityService capabilityService = mock(WebConsoleCapabilityService.class);
        JChatMind agent = mock(JChatMind.class);
        Executor directExecutor = Runnable::run;

        when(chatSessionMapper.selectById("session-1")).thenReturn(ChatSession.builder()
                .id("session-1")
                .agentId("agent-1")
                .title("web")
                .metadata("{\"channel\":\"WEB_CONSOLE\",\"repoId\":\"repo-1\",\"model\":\"gpt-5.5\"}")
                .build());
        when(codeRepositoryMapper.selectById("repo-1")).thenReturn(CodeRepository.builder()
                .id("repo-1")
                .name("hm-dianping")
                .status("READY")
                .build());
        when(chatMessageFacadeService.agentCreateChatMessage(isA(CreateChatMessageRequest.class)))
                .thenReturn(CreateChatMessageResponse.builder().chatMessageId("user-message-1").build());
        GetWebConsoleCapabilitiesResponse capabilities = capabilities("repo-1", "gpt-5.5");
        when(capabilityService.getCapabilities("repo-1", "gpt-5.5")).thenReturn(capabilities);
        when(capabilityService.runtimeCapabilityContext(capabilities)).thenReturn("capability context");
        when(capabilityService.safeFullOptionalToolNames())
                .thenReturn(List.of("searchProjectCode", "getCodeChunk", "databaseQuery"));
        when(jChatMindFactory.create(eq("agent-1"), eq("session-1"), eq("user-message-1"),
                isA(String.class), isA(String.class), eq("gpt-5.5"),
                eq(List.of("searchProjectCode", "getCodeChunk", "databaseQuery")),
                eq("repo-1"))).thenReturn(agent);

        WebConsoleChatServiceImpl service = new WebConsoleChatServiceImpl(
                chatSessionMapper,
                codeRepositoryMapper,
                chatMessageFacadeService,
                jChatMindFactory,
                chatClientRegistry,
                new ObjectMapper(),
                eventPublisher,
                capabilityService,
                directExecutor);
        WebConsoleChatSendRequest request = new WebConsoleChatSendRequest();
        request.setConversationId("session-1");
        request.setAgentId("agent-1");
        request.setModel("gpt-5.5");
        request.setRepoId("repo-1");
        request.setContent("分析秒杀下单链路是怎么走的");

        WebConsoleChatSendResponse response = service.send(request);

        ArgumentCaptor<CreateChatMessageRequest> messageCaptor =
                ArgumentCaptor.forClass(CreateChatMessageRequest.class);
        verify(chatMessageFacadeService).agentCreateChatMessage(messageCaptor.capture());
        assertEquals(ChatMessageDTO.RoleType.USER, messageCaptor.getValue().getRole());
        assertEquals("session-1", messageCaptor.getValue().getSessionId());
        assertEquals("分析秒杀下单链路是怎么走的", messageCaptor.getValue().getContent());
        assertEquals("gpt-5.5", messageCaptor.getValue().getMetadata().getModel());
        assertFalse(messageCaptor.getValue().getContent().contains("Feishu context"));

        ArgumentCaptor<String> runtimeContextCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> traceIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(jChatMindFactory).create(eq("agent-1"), eq("session-1"), eq("user-message-1"),
                runtimeContextCaptor.capture(), traceIdCaptor.capture(), eq("gpt-5.5"),
                eq(List.of("searchProjectCode", "getCodeChunk", "databaseQuery")),
                eq("repo-1"));
        org.assertj.core.api.Assertions.assertThat(runtimeContextCaptor.getValue())
                .contains("channel: WEB_CONSOLE")
                .contains("assistant: 代码助手")
                .contains("selectedRepoId: repo-1")
                .contains("selectedRepoName: hm-dianping")
                .contains("selectedModel: gpt-5.5")
                .contains("selectedConversationId: session-1")
                .contains("capability context")
                .contains("Do not add or mention Feishu context");
        verify(agent).setMaxLoopSteps(12);
        verify(agent).setFinalStreamingEnabled(false);
        verify(agent).run(anyString());

        assertEquals("user-message-1", response.getUserMessageId());
        assertEquals("session-1", response.getConversationId());
        assertEquals("/sse/connect/session-1", response.getSseUrl());
        assertEquals(traceIdCaptor.getValue(), response.getRunId());
        assertNotNull(response.getRunId());
    }

    @Test
    void enabledFlagIsAppliedOnlyToWebConsoleAgentRuntime() {
        ChatSessionMapper chatSessionMapper = mock(ChatSessionMapper.class);
        CodeRepositoryMapper codeRepositoryMapper = mock(CodeRepositoryMapper.class);
        AgentTaskLifecycleService lifecycleService = mock(AgentTaskLifecycleService.class);
        AgentTaskLogService logService = mock(AgentTaskLogService.class);
        AgentTaskRuntimeRegistry runtimeRegistry = new AgentTaskRuntimeRegistry();
        JChatMindFactory factory = mock(JChatMindFactory.class);
        JChatMind agent = mock(JChatMind.class);
        ChatClientRegistry clients = new ChatClientRegistry(
                Map.of("gpt-compatible-chat", mock(ChatClient.class, RETURNS_DEEP_STUBS)));
        WebConsoleCapabilityService capabilityService = mock(WebConsoleCapabilityService.class);

        when(chatSessionMapper.selectById("session-stream")).thenReturn(ChatSession.builder()
                .id("session-stream").agentId("agent-1")
                .metadata("{\"channel\":\"WEB_CONSOLE\",\"repoId\":\"repo-1\",\"model\":\"gpt-5.5\"}")
                .build());
        when(codeRepositoryMapper.selectById("repo-1")).thenReturn(CodeRepository.builder()
                .id("repo-1").name("repo").status("READY").build());
        GetWebConsoleCapabilitiesResponse capabilities = capabilities("repo-1", "gpt-5.5");
        when(capabilityService.getCapabilities("repo-1", "gpt-5.5")).thenReturn(capabilities);
        when(capabilityService.runtimeCapabilityContext(capabilities)).thenReturn("capability context");
        when(capabilityService.safeFullOptionalToolNames())
                .thenReturn(List.of("searchProjectCode", "getCodeChunk", "databaseQuery"));
        when(lifecycleService.reserve(eq("session-stream"), eq("agent-1"), eq("gpt-5.5"),
                eq(12), anyString(), isA(CreateChatMessageRequest.class)))
                .thenReturn(new AgentTaskLifecycleService.ReservedTask(
                        AgentTask.builder().id("task-stream").sessionId("session-stream").build(),
                        "user-message-stream"));
        when(factory.create(eq("agent-1"), eq("session-stream"), eq("user-message-stream"),
                anyString(), anyString(), eq("gpt-5.5"), isA(List.class), eq("repo-1")))
                .thenReturn(agent);

        WebConsoleChatServiceImpl service = new WebConsoleChatServiceImpl(
                chatSessionMapper, codeRepositoryMapper, lifecycleService, logService, runtimeRegistry,
                factory, clients, new ObjectMapper(), mock(AgentEventPublisher.class), capabilityService,
                Runnable::run, true);
        WebConsoleChatSendRequest request = new WebConsoleChatSendRequest();
        request.setConversationId("session-stream");
        request.setRepoId("repo-1");
        request.setModel("gpt-5.5");
        request.setContent("question");

        service.send(request);

        verify(agent).setFinalStreamingEnabled(true);
        verify(agent).run("task-stream");
    }

    @Test
    void sendKeepsChineseUserMessageReadable() {
        ChatSessionMapper chatSessionMapper = mock(ChatSessionMapper.class);
        CodeRepositoryMapper codeRepositoryMapper = mock(CodeRepositoryMapper.class);
        ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        JChatMindFactory jChatMindFactory = mock(JChatMindFactory.class);
        ChatClientRegistry chatClientRegistry = new ChatClientRegistry(
                Map.of("deepseek-official-chat", mock(ChatClient.class, RETURNS_DEEP_STUBS)));
        AgentEventPublisher eventPublisher = mock(AgentEventPublisher.class);
        WebConsoleCapabilityService capabilityService = mock(WebConsoleCapabilityService.class);
        JChatMind agent = mock(JChatMind.class);
        Executor directExecutor = Runnable::run;

        when(chatSessionMapper.selectById("session-cn")).thenReturn(ChatSession.builder()
                .id("session-cn")
                .agentId("agent-1")
                .title("Web Console 中文测试")
                .metadata("{\"channel\":\"WEB_CONSOLE\",\"repoId\":\"repo-1\",\"model\":\"deepseek-chat\"}")
                .build());
        when(codeRepositoryMapper.selectById("repo-1")).thenReturn(CodeRepository.builder()
                .id("repo-1")
                .name("hm-dianping")
                .status("READY")
                .build());
        when(chatMessageFacadeService.agentCreateChatMessage(isA(CreateChatMessageRequest.class)))
                .thenReturn(CreateChatMessageResponse.builder().chatMessageId("user-message-cn").build());
        GetWebConsoleCapabilitiesResponse capabilities = capabilities("repo-1", "deepseek-chat");
        when(capabilityService.getCapabilities("repo-1", "deepseek-chat")).thenReturn(capabilities);
        when(capabilityService.runtimeCapabilityContext(capabilities)).thenReturn("capability context");
        when(capabilityService.safeFullOptionalToolNames())
                .thenReturn(List.of("searchProjectCode", "getCodeChunk", "databaseQuery"));
        when(jChatMindFactory.create(eq("agent-1"), eq("session-cn"), eq("user-message-cn"),
                isA(String.class), isA(String.class), eq("deepseek-chat"),
                eq(List.of("searchProjectCode", "getCodeChunk", "databaseQuery")),
                eq("repo-1"))).thenReturn(agent);

        WebConsoleChatServiceImpl service = new WebConsoleChatServiceImpl(
                chatSessionMapper,
                codeRepositoryMapper,
                chatMessageFacadeService,
                jChatMindFactory,
                chatClientRegistry,
                new ObjectMapper(),
                eventPublisher,
                capabilityService,
                directExecutor);
        WebConsoleChatSendRequest request = new WebConsoleChatSendRequest();
        request.setConversationId("session-cn");
        request.setAgentId("agent-1");
        request.setModel("deepseek-chat");
        request.setRepoId("repo-1");
        request.setContent("分析秒杀下单链路");

        service.send(request);

        ArgumentCaptor<CreateChatMessageRequest> messageCaptor =
                ArgumentCaptor.forClass(CreateChatMessageRequest.class);
        verify(chatMessageFacadeService).agentCreateChatMessage(messageCaptor.capture());
        assertEquals("分析秒杀下单链路", messageCaptor.getValue().getContent());
        assertFalse(messageCaptor.getValue().getContent().contains("????"));
    }

    @Test
    void sendPassesEnabledMcpToolsIntoRuntimeAllowedTools() {
        ChatSessionMapper chatSessionMapper = mock(ChatSessionMapper.class);
        CodeRepositoryMapper codeRepositoryMapper = mock(CodeRepositoryMapper.class);
        ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        JChatMindFactory jChatMindFactory = mock(JChatMindFactory.class);
        ChatClientRegistry chatClientRegistry = new ChatClientRegistry(
                Map.of("gpt-compatible-chat", mock(ChatClient.class, RETURNS_DEEP_STUBS)));
        AgentEventPublisher eventPublisher = mock(AgentEventPublisher.class);
        WebConsoleCapabilityService capabilityService = mock(WebConsoleCapabilityService.class);
        JChatMind agent = mock(JChatMind.class);
        Executor directExecutor = Runnable::run;

        when(chatSessionMapper.selectById("session-mcp")).thenReturn(ChatSession.builder()
                .id("session-mcp")
                .agentId("agent-1")
                .title("MCP runtime")
                .metadata("{\"channel\":\"WEB_CONSOLE\",\"repoId\":\"repo-1\",\"model\":\"gpt-5.5\"}")
                .build());
        when(codeRepositoryMapper.selectById("repo-1")).thenReturn(CodeRepository.builder()
                .id("repo-1")
                .name("hm-dianping")
                .status("READY")
                .build());
        when(chatMessageFacadeService.agentCreateChatMessage(isA(CreateChatMessageRequest.class)))
                .thenReturn(CreateChatMessageResponse.builder().chatMessageId("user-message-mcp").build());
        GetWebConsoleCapabilitiesResponse capabilities = capabilitiesWithMcp("repo-1", "gpt-5.5");
        when(capabilityService.getCapabilities("repo-1", "gpt-5.5")).thenReturn(capabilities);
        when(capabilityService.runtimeCapabilityContext(capabilities)).thenReturn("capability context");
        when(capabilityService.safeFullOptionalToolNames()).thenReturn(List.of(
                "searchProjectCode",
                "getCodeChunk",
                "databaseQuery",
                "mcp_context7_resolve_library_id",
                "mcp_github_mcp_server_search_code",
                "mcp_playwright_browser_navigate"
        ));
        when(jChatMindFactory.create(eq("agent-1"), eq("session-mcp"), eq("user-message-mcp"),
                isA(String.class), isA(String.class), eq("gpt-5.5"), isA(List.class), eq("repo-1")))
                .thenReturn(agent);

        WebConsoleChatServiceImpl service = new WebConsoleChatServiceImpl(
                chatSessionMapper,
                codeRepositoryMapper,
                chatMessageFacadeService,
                jChatMindFactory,
                chatClientRegistry,
                new ObjectMapper(),
                eventPublisher,
                capabilityService,
                directExecutor);
        WebConsoleChatSendRequest request = new WebConsoleChatSendRequest();
        request.setConversationId("session-mcp");
        request.setAgentId("agent-1");
        request.setModel("gpt-5.5");
        request.setRepoId("repo-1");
        request.setContent("search current docs");

        service.send(request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> runtimeToolsCaptor = ArgumentCaptor.forClass(List.class);
        verify(jChatMindFactory).create(eq("agent-1"), eq("session-mcp"), eq("user-message-mcp"),
                isA(String.class), isA(String.class), eq("gpt-5.5"), runtimeToolsCaptor.capture(),
                eq("repo-1"));
        org.assertj.core.api.Assertions.assertThat(runtimeToolsCaptor.getValue()).containsExactly(
                "searchProjectCode",
                "getCodeChunk",
                "databaseQuery",
                "mcp_context7_resolve_library_id",
                "mcp_github_mcp_server_search_code",
                "mcp_playwright_browser_navigate");
    }

    private GetWebConsoleCapabilitiesResponse capabilities(String repoId, String model) {
        return GetWebConsoleCapabilitiesResponse.builder()
                .assistant("代码助手")
                .profile(WebConsoleCapabilityService.PROFILE)
                .repoId(repoId)
                .model(model)
                .capabilities(List.of(
                        WebConsoleCapabilityVO.builder()
                                .key("code_search")
                                .label("代码检索")
                                .enabled(true)
                                .tools(List.of("searchProjectCode", "getCodeChunk"))
                                .build(),
                        WebConsoleCapabilityVO.builder()
                                .key("database_readonly")
                                .label("数据库只读查询")
                                .enabled(true)
                                .tools(List.of("databaseQuery"))
                                .build()
                ))
                .notSupported(List.of("shell", "apply_patch", "write_file"))
                .build();
    }

    private GetWebConsoleCapabilitiesResponse capabilitiesWithMcp(String repoId, String model) {
        return GetWebConsoleCapabilitiesResponse.builder()
                .assistant("code assistant")
                .profile(WebConsoleCapabilityService.PROFILE)
                .repoId(repoId)
                .model(model)
                .capabilities(List.of(
                        WebConsoleCapabilityVO.builder()
                                .key("code_search")
                                .enabled(true)
                                .tools(List.of("searchProjectCode", "getCodeChunk"))
                                .build(),
                        WebConsoleCapabilityVO.builder()
                                .key("database_readonly")
                                .enabled(true)
                                .tools(List.of("databaseQuery"))
                                .build(),
                        WebConsoleCapabilityVO.builder()
                                .key("knowledge_rag")
                                .enabled(true)
                                .tools(List.of("knowledgeQuery"))
                                .build(),
                        WebConsoleCapabilityVO.builder()
                                .key("agent_control")
                                .enabled(true)
                                .tools(List.of("terminate"))
                                .build(),
                        WebConsoleCapabilityVO.builder()
                                .key("mcp_docs")
                                .enabled(true)
                                .tools(List.of("mcp_context7_resolve_library_id"))
                                .build(),
                        WebConsoleCapabilityVO.builder()
                                .key("mcp_github")
                                .enabled(true)
                                .tools(List.of("mcp_github_mcp_server_search_code"))
                                .build(),
                        WebConsoleCapabilityVO.builder()
                                .key("mcp_browser")
                                .enabled(true)
                                .tools(List.of("mcp_playwright_browser_navigate"))
                                .build()
                ))
                .notSupported(List.of("shell", "apply_patch", "write_file"))
                .build();
    }
}
