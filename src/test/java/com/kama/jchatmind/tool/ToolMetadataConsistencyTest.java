package com.kama.jchatmind.tool;

import com.kama.jchatmind.agent.tools.CodeSearchTools;
import com.kama.jchatmind.agent.tools.CodeChunkTools;
import com.kama.jchatmind.agent.tools.DataBaseTools;
import com.kama.jchatmind.agent.tools.KnowledgeTools;
import com.kama.jchatmind.agent.tools.TerminateTool;
import com.kama.jchatmind.agent.tools.Tool;
import com.kama.jchatmind.service.CodeRagAnswerEvidenceService;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.mapper.CodeChunkMapper;
import com.kama.jchatmind.mapper.CodeRepositoryMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ToolMetadataConsistencyTest {

    @Test
    void toolBeansMatchAnnotationsAndRegistryDefinitions() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        List<Tool> tools = List.of(
                new KnowledgeTools(mock(RagService.class), registry),
                new CodeSearchTools(mock(CodeRagAnswerEvidenceService.class)),
                new CodeChunkTools(mock(CodeChunkMapper.class), mock(CodeRepositoryMapper.class)),
                new DataBaseTools(mock(JdbcTemplate.class)),
                new TerminateTool()
        );
        registry.initialize(tools);

        for (Tool tool : tools) {
            Method toolMethod = annotatedToolMethod(tool);
            org.springframework.ai.tool.annotation.Tool annotation =
                    toolMethod.getAnnotation(org.springframework.ai.tool.annotation.Tool.class);

            assertEquals(tool.getName(), annotation.name(), tool.getClass().getSimpleName());
            assertEquals(tool.getDescription(), annotation.description(), tool.getClass().getSimpleName());
            assertTrue(registry.find(tool.getName()).isPresent(), tool.getName());
            assertTrue(registry.canExposeToAgent(tool.getName()), tool.getName());
        }
    }

    private Method annotatedToolMethod(Tool tool) {
        return List.of(tool.getClass().getDeclaredMethods()).stream()
                .filter(method -> method.isAnnotationPresent(org.springframework.ai.tool.annotation.Tool.class))
                .findFirst()
                .orElseThrow();
    }
}
