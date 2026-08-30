package com.kama.jchatmind.mcp;

import com.kama.jchatmind.agent.tools.CodeSearchTools;
import com.kama.jchatmind.agent.tools.CodeChunkTools;
import com.kama.jchatmind.agent.tools.DataBaseTools;
import com.kama.jchatmind.agent.tools.KnowledgeTools;
import com.kama.jchatmind.agent.tools.TerminateTool;
import com.kama.jchatmind.service.CodeRagAnswerEvidenceService;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.mapper.CodeChunkMapper;
import com.kama.jchatmind.mapper.CodeRepositoryMapper;
import com.kama.jchatmind.tool.InMemoryToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class McpLocalToolRegistryIsolationTest {

    @Test
    void localToolRegistryBehaviorRemainsUnchangedAndDoesNotKnowExternalMcpTools() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.initialize(List.of(
                new KnowledgeTools(mock(RagService.class), registry),
                new CodeSearchTools(mock(CodeRagAnswerEvidenceService.class)),
                new CodeChunkTools(mock(CodeChunkMapper.class), mock(CodeRepositoryMapper.class)),
                new DataBaseTools(mock(JdbcTemplate.class)),
                new TerminateTool()
        ));

        assertTrue(registry.canExposeToAgent("databaseQuery"));
        assertTrue(registry.canExposeToAgent("searchProjectCode"));
        assertTrue(registry.canExposeToAgent("getCodeChunk"));
        assertTrue(registry.canExposeToAgent("knowledgeQuery"));
        assertTrue(registry.canExposeToAgent("terminate"));
        assertFalse(registry.canExposeToAgent("mcp_docs_search_docs"));
    }
}
