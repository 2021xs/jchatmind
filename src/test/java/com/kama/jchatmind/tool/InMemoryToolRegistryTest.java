package com.kama.jchatmind.tool;

import com.kama.jchatmind.agent.tools.Tool;
import com.kama.jchatmind.agent.tools.ToolType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryToolRegistryTest {

    @Test
    void registersToolMetadataAndCanonicalizesAlias() {
        InMemoryToolRegistry registry = registry(
                List.of(policy(AlphaTool.class, true, 40, "legacyAlpha")),
                List.of(new AlphaTool()));

        ToolDefinition definition = registry.find("alpha").orElseThrow();
        assertEquals("alpha", definition.getToolName());
        assertEquals("Alpha description", definition.getDescription());
        assertEquals(ToolType.FIXED, definition.getType());
        assertEquals("alpha", registry.canonicalName("legacyAlpha"));
        assertTrue(registry.canExposeToAgent("alpha"));
        assertEquals("12345678", registry.truncateResult("alpha", "12345678"));
        String truncated = registry.truncateResult("alpha", "x".repeat(80));
        assertTrue(truncated.length() <= 40);
        assertTrue(truncated.endsWith("...[truncated]"));
    }

    @Test
    void rejectsDuplicateToolNames() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry(List.of(
                policy(AlphaTool.class, true, 100),
                policy(DuplicateAlphaTool.class, true, 100)));

        assertThrows(IllegalStateException.class,
                () -> registry.initialize(List.of(new AlphaTool(), new DuplicateAlphaTool())));
    }

    @Test
    void rejectsAliasConflict() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry(List.of(
                policy(AlphaTool.class, true, 100, "beta"),
                policy(BetaTool.class, true, 100)));

        assertThrows(IllegalStateException.class,
                () -> registry.initialize(List.of(new AlphaTool(), new BetaTool())));
    }

    @Test
    void rejectsPolicyPointingToMissingTool() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry(List.of(
                policy(AlphaTool.class, true, 100),
                policy(BetaTool.class, true, 100)));

        assertThrows(IllegalStateException.class, () -> registry.initialize(List.of(new BetaTool())));
    }

    @Test
    void rejectsToolWithoutPolicyAndInvalidMaxResultLength() {
        InMemoryToolRegistry missingPolicy = new InMemoryToolRegistry(List.of(policy(AlphaTool.class, true, 100)));
        assertThrows(IllegalStateException.class,
                () -> missingPolicy.initialize(List.of(new AlphaTool(), new BetaTool())));

        InMemoryToolRegistry invalidLength = new InMemoryToolRegistry(List.of(policy(AlphaTool.class, true, 0)));
        assertThrows(IllegalStateException.class, () -> invalidLength.initialize(List.of(new AlphaTool())));
    }

    @Test
    void disabledToolCannotBeExposedOrExecuted() {
        InMemoryToolRegistry registry = registry(
                List.of(policy(AlphaTool.class, false, 100)),
                List.of(new AlphaTool()));

        assertFalse(registry.canExposeToAgent("alpha"));
        assertFalse(registry.isAllowedForRuntime("alpha", List.of("alpha")));
    }

    private InMemoryToolRegistry registry(List<ToolPolicy> policies, List<Tool> tools) {
        InMemoryToolRegistry registry = new InMemoryToolRegistry(policies);
        registry.initialize(tools);
        return registry;
    }

    private ToolPolicy policy(Class<? extends Tool> toolClass, boolean enabled, int maxResultLength,
                              String... aliases) {
        return ToolPolicy.builder()
                .toolClass(toolClass)
                .enabled(enabled)
                .allowInAgent(true)
                .maxResultLength(maxResultLength)
                .aliases(List.of(aliases))
                .build();
    }

    static class AlphaTool implements Tool {
        @Override
        public ToolType getType() {
            return ToolType.FIXED;
        }

        @org.springframework.ai.tool.annotation.Tool(name = "alpha", description = "Alpha description")
        public String run() {
            return "alpha";
        }
    }

    static class DuplicateAlphaTool implements Tool {
        @Override
        public ToolType getType() {
            return ToolType.OPTIONAL;
        }

        @org.springframework.ai.tool.annotation.Tool(name = "alpha", description = "Duplicate")
        public String run() {
            return "duplicate";
        }
    }

    static class BetaTool implements Tool {
        @Override
        public ToolType getType() {
            return ToolType.OPTIONAL;
        }

        @org.springframework.ai.tool.annotation.Tool(name = "beta", description = "Beta description")
        public String run() {
            return "beta";
        }
    }
}
