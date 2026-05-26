package com.kama.jchatmind.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryToolRegistryTest {

    private final InMemoryToolRegistry registry = new InMemoryToolRegistry();

    @Test
    void shouldCanonicalizeLegacyToolNames() {
        assertEquals("databaseQuery", registry.canonicalName("dataBaseTool"));
        assertEquals("knowledgeQuery", registry.canonicalName("KnowledgeTool"));
    }

    @Test
    void shouldCheckGlobalExposureForKnownTools() {
        assertTrue(registry.canExposeToAgent("databaseQuery"));
        assertFalse(registry.canExposeToAgent("notExistingTool"));
    }

    @Test
    void shouldCheckRuntimeAllowanceWithCanonicalNames() {
        assertTrue(registry.isAllowedForRuntime("dataBaseTool", List.of("databaseQuery")));
        assertTrue(registry.isAllowedForRuntime("databaseQuery", List.of("dataBaseTool")));
        assertFalse(registry.isAllowedForRuntime("notExistingTool", List.of("databaseQuery")));
        assertFalse(registry.isAllowedForRuntime("databaseQuery", List.of("searchProjectCode")));
    }
}
