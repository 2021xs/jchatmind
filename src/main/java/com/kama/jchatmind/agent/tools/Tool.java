package com.kama.jchatmind.agent.tools;

public interface Tool {
    default String getName() {
        return annotatedMethod().getAnnotation(org.springframework.ai.tool.annotation.Tool.class).name();
    }

    default String getDescription() {
        return annotatedMethod().getAnnotation(org.springframework.ai.tool.annotation.Tool.class).description();
    }

    ToolType getType();

    private java.lang.reflect.Method annotatedMethod() {
        java.util.List<java.lang.reflect.Method> methods = java.util.Arrays.stream(getClass().getMethods())
                .filter(method -> method.isAnnotationPresent(org.springframework.ai.tool.annotation.Tool.class))
                .toList();
        if (methods.size() != 1) {
            throw new IllegalStateException("Tool bean must declare exactly one @Tool method: " + getClass().getName());
        }
        return methods.get(0);
    }
}
