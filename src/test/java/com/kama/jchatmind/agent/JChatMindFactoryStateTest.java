package com.kama.jchatmind.agent;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JChatMindFactoryStateTest {

    @Test
    void singletonFactoryKeepsNoMutableRequestState() {
        List<String> mutableInstanceFields = Arrays.stream(JChatMindFactory.class.getDeclaredFields())
                .filter(field -> !isStaticOrFinal(field))
                .map(Field::getName)
                .toList();

        assertTrue(mutableInstanceFields.isEmpty(),
                "JChatMindFactory must not keep request-level mutable state: " + mutableInstanceFields);
    }

    private boolean isStaticOrFinal(Field field) {
        int modifiers = field.getModifiers();
        return Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers);
    }
}
