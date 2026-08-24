package com.kama.jchatmind.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskEvalFixtureTest {
    private static final Set<String> KNOWN_TOOLS = Set.of(
            "searchProjectCode", "databaseQuery", "knowledgeQuery", "terminate");

    @Test
    void fixtureContainsThirtyValidAuditableCases() throws Exception {
        List<AgentTaskEvalCase> cases = new ObjectMapper().readValue(
                new ClassPathResource("eval/agent_task_eval_cases.json").getInputStream(),
                new TypeReference<>() {
                });

        assertEquals(30, cases.size());
        assertEquals(30, cases.stream().map(evalCase -> evalCase.id).distinct().count());
        assertEquals(12, count(cases, "BASIC"));
        assertEquals(12, count(cases, "MEDIUM"));
        assertEquals(6, count(cases, "HARD"));
        assertTrue(cases.stream().allMatch(AgentTaskEvalCase::valid));
        assertTrue(cases.stream().flatMap(evalCase -> AgentTaskEvalCase.safe(evalCase.requiredTools).stream())
                .allMatch(KNOWN_TOOLS::contains));
        assertTrue(cases.stream().anyMatch(evalCase -> evalCase.requiredTools.size() > 1));
        assertTrue(cases.stream().anyMatch(evalCase -> evalCase.requiredTools.isEmpty()));
    }

    private long count(List<AgentTaskEvalCase> cases, String difficulty) {
        return cases.stream().filter(evalCase -> difficulty.equals(evalCase.difficulty)).count();
    }
}
