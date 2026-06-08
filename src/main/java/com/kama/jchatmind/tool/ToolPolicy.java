package com.kama.jchatmind.tool;

import com.kama.jchatmind.agent.tools.Tool;
import lombok.Builder;
import lombok.Data;
import lombok.Singular;

import java.util.Set;

@Data
@Builder
public class ToolPolicy {
    private Class<? extends Tool> toolClass;
    private boolean enabled;
    private boolean allowInAgent;
    private int maxResultLength;
    @Singular
    private Set<String> aliases;
}
