package com.kama.jchatmind.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kama.jchatmind.config.ToolDuplicateDetectionProperties;
import com.kama.jchatmind.tool.ToolDuplicateCallState;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class ToolDuplicateCallDetector {
    private final ObjectMapper objectMapper;
    private final ObjectReader jsonReader;
    private final ToolDuplicateDetectionProperties properties;

    public ToolDuplicateCallDetector(ObjectMapper objectMapper,
                                     ToolDuplicateDetectionProperties properties) {
        this.objectMapper = objectMapper;
        this.jsonReader = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.properties = properties;
    }

    public DuplicateCheck check(ToolDuplicateCallState state,
                                String canonicalToolName,
                                String argumentsJson) {
        if (!properties.isEnabled() || state == null) {
            return DuplicateCheck.skipped();
        }

        String canonicalArguments;
        try {
            JsonNode arguments = jsonReader.readTree(argumentsJson);
            if (arguments == null) {
                state.resetSequence();
                return DuplicateCheck.skipped();
            }
            canonicalArguments = objectMapper.writeValueAsString(sortObjectKeys(arguments));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            state.resetSequence();
            return DuplicateCheck.skipped();
        }

        String duplicateKey = canonicalToolName + "|" + canonicalArguments;
        ToolDuplicateCallState.Observation observation = state.observe(
                duplicateKey, properties.validatedMaxConsecutiveSameCalls());
        return new DuplicateCheck(
                observation.action(),
                duplicateKey,
                canonicalArguments,
                observation.consecutiveCount(),
                properties.getMaxConsecutiveSameCalls());
    }

    private JsonNode sortObjectKeys(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            List<String> fieldNames = new ArrayList<>();
            node.fieldNames().forEachRemaining(fieldNames::add);
            Collections.sort(fieldNames);
            for (String fieldName : fieldNames) {
                sorted.set(fieldName, sortObjectKeys(node.get(fieldName)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode ordered = objectMapper.createArrayNode();
            for (JsonNode element : node) {
                ordered.add(sortObjectKeys(element));
            }
            return ordered;
        }
        return node.deepCopy();
    }

    public record DuplicateCheck(
            ToolDuplicateCallState.Action action,
            String duplicateKey,
            String canonicalArguments,
            int consecutiveCount,
            int maxConsecutiveSameCalls) {

        private static DuplicateCheck skipped() {
            return new DuplicateCheck(null, null, null, 0, 0);
        }

        public boolean rejected() {
            return action == ToolDuplicateCallState.Action.REJECTED
                    || action == ToolDuplicateCallState.Action.HARD_STOP;
        }

        public boolean hardStop() {
            return action == ToolDuplicateCallState.Action.HARD_STOP;
        }
    }
}
