package com.kama.jchatmind.tool;

import com.kama.jchatmind.agent.tools.CodeSearchTools;
import com.kama.jchatmind.agent.tools.CodeChunkTools;
import com.kama.jchatmind.agent.tools.DataBaseTools;
import com.kama.jchatmind.agent.tools.KnowledgeTools;
import com.kama.jchatmind.agent.tools.TerminateTool;
import com.kama.jchatmind.agent.tools.Tool;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class InMemoryToolRegistry implements ToolRegistry {
    private static final int DEFAULT_MAX_RESULT_LENGTH = 4000;

    private final List<ToolPolicy> policies;
    private List<ToolDefinition> definitions = List.of();

    public InMemoryToolRegistry() {
        this(defaultPolicies());
    }

    InMemoryToolRegistry(List<ToolPolicy> policies) {
        this.policies = List.copyOf(policies);
    }

    public void initialize(Collection<Tool> tools) {
        List<Tool> safeTools = tools == null ? List.of() : List.copyOf(tools);
        validatePolicies();
        validateToolNames(safeTools);

        Map<Class<? extends Tool>, ToolPolicy> policyByClass = new HashMap<>();
        for (ToolPolicy policy : policies) {
            policyByClass.put(policy.getToolClass(), policy);
        }

        List<ToolDefinition> resolved = new ArrayList<>();
        for (Tool tool : safeTools) {
            ToolPolicy policy = policyFor(tool, policyByClass)
                    .orElseThrow(() -> new IllegalStateException(
                            "Tool bean has no runtime policy: " + tool.getClass().getName()));
            resolved.add(ToolDefinition.builder()
                    .toolName(tool.getName())
                    .description(tool.getDescription())
                    .type(tool.getType())
                    .enabled(policy.isEnabled())
                    .allowInAgent(policy.isAllowInAgent())
                    .maxResultLength(policy.getMaxResultLength())
                    .aliases(policy.getAliases())
                    .build());
        }
        for (ToolPolicy policy : policies) {
            boolean exists = safeTools.stream().anyMatch(tool -> policyMatches(policy, tool));
            if (!exists) {
                throw new IllegalStateException("Tool policy points to missing Tool bean: "
                        + policy.getToolClass().getName());
            }
        }
        validateAliases(resolved);
        definitions = List.copyOf(resolved);
    }

    @Override
    public Optional<ToolDefinition> find(String toolNameOrAlias) {
        return definitions.stream()
                .filter(definition -> definition.matches(toolNameOrAlias))
                .findFirst();
    }

    @Override
    public String canonicalName(String toolNameOrAlias) {
        return find(toolNameOrAlias)
                .map(ToolDefinition::getToolName)
                .orElse(toolNameOrAlias);
    }

    @Override
    public boolean canExposeToAgent(String toolNameOrAlias) {
        return find(toolNameOrAlias)
                .map(definition -> definition.isEnabled() && definition.isAllowInAgent())
                .orElse(false);
    }

    @Override
    public boolean isAllowedForRuntime(String toolNameOrAlias, Collection<String> runtimeToolNames) {
        ToolDefinition requested = find(toolNameOrAlias).orElse(null);
        if (requested == null || !requested.isEnabled()) {
            return false;
        }
        String requestedCanonical = requested.getToolName();
        return runtimeToolNames != null && runtimeToolNames.stream()
                .map(this::canonicalName)
                .anyMatch(name -> equalsIgnoreCase(name, requestedCanonical));
    }

    @Override
    public int maxResultLength(String toolNameOrAlias) {
        return find(toolNameOrAlias)
                .map(ToolDefinition::getMaxResultLength)
                .filter(length -> length > 0)
                .orElse(DEFAULT_MAX_RESULT_LENGTH);
    }

    @Override
    public String truncateResult(String toolNameOrAlias, String result) {
        if (result == null) {
            return null;
        }
        int maxLength = maxResultLength(toolNameOrAlias);
        if (result.length() <= maxLength) {
            return result;
        }
        int keep = Math.max(0, maxLength - 32);
        return result.substring(0, keep) + "\n...[truncated]";
    }

    private Optional<ToolPolicy> policyFor(Tool tool, Map<Class<? extends Tool>, ToolPolicy> policyByClass) {
        Class<?> targetClass = AopUtils.getTargetClass(tool);
        return policyByClass.entrySet().stream()
                .filter(entry -> entry.getKey().isAssignableFrom(targetClass))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    private boolean policyMatches(ToolPolicy policy, Tool tool) {
        return policy.getToolClass().isAssignableFrom(AopUtils.getTargetClass(tool));
    }

    private void validatePolicies() {
        Set<Class<? extends Tool>> classes = new HashSet<>();
        for (ToolPolicy policy : policies) {
            if (policy.getToolClass() == null) {
                throw new IllegalStateException("Tool policy is missing toolClass");
            }
            if (!classes.add(policy.getToolClass())) {
                throw new IllegalStateException("Duplicate Tool policy: " + policy.getToolClass().getName());
            }
            if (policy.getMaxResultLength() <= 0) {
                throw new IllegalStateException("Tool policy has invalid maxResultLength: "
                        + policy.getToolClass().getName());
            }
        }
    }

    private void validateToolNames(Collection<Tool> tools) {
        Set<String> names = new HashSet<>();
        for (Tool tool : tools) {
            if (!StringUtils.hasText(tool.getName())) {
                throw new IllegalStateException("Tool bean has blank @Tool name: " + tool.getClass().getName());
            }
            if (!names.add(normalize(tool.getName()))) {
                throw new IllegalStateException("Duplicate Tool name: " + tool.getName());
            }
        }
    }

    private void validateAliases(Collection<ToolDefinition> resolved) {
        Set<String> claimedNames = new HashSet<>();
        for (ToolDefinition definition : resolved) {
            claimedNames.add(normalize(definition.getToolName()));
        }
        for (ToolDefinition definition : resolved) {
            for (String alias : definition.getAliases()) {
                if (!StringUtils.hasText(alias) || !claimedNames.add(normalize(alias))) {
                    throw new IllegalStateException("Conflicting Tool alias: " + alias);
                }
            }
        }
    }

    private boolean equalsIgnoreCase(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static List<ToolPolicy> defaultPolicies() {
        return List.of(
                policy(KnowledgeTools.class, true, true, 6000, "KnowledgeTool"),
                policy(CodeSearchTools.class, true, true, 7000),
                policy(CodeChunkTools.class, true, true, 8000),
                policy(DataBaseTools.class, true, true, 4000, "dataBaseTool"),
                policy(TerminateTool.class, true, true, 1000)
        );
    }

    private static ToolPolicy policy(Class<? extends Tool> toolClass, boolean enabled,
                                     boolean allowInAgent, int maxResultLength, String... aliases) {
        return ToolPolicy.builder()
                .toolClass(toolClass)
                .enabled(enabled)
                .allowInAgent(allowInAgent)
                .maxResultLength(maxResultLength)
                .aliases(Set.of(aliases))
                .build();
    }
}
