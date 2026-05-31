package com.kama.jchatmind.integration.feishu;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeishuRepoResolverTest {

    private static final String TEST_REPO_ID = "ac61fb27-e3cd-4193-9620-b6d50ef8f096";

    @Test
    void resolvesUuidDirectly() {
        FeishuRepoResolver resolver = new FeishuRepoResolver(new FeishuProperties());

        assertEquals(TEST_REPO_ID, resolver.resolveRepoId(TEST_REPO_ID).orElseThrow());
    }

    @Test
    void resolvesConfiguredAlias() {
        FeishuProperties properties = new FeishuProperties();
        properties.setRepoAliases(Map.of("黑马点评", TEST_REPO_ID, "hmdp", TEST_REPO_ID));
        FeishuRepoResolver resolver = new FeishuRepoResolver(properties);

        assertEquals(TEST_REPO_ID, resolver.resolveRepoId("黑马点评").orElseThrow());
        assertEquals(TEST_REPO_ID, resolver.resolveRepoId("hmdp").orElseThrow());
    }

    @Test
    void ignoresBlankOrInvalidAliasValues() {
        FeishuProperties properties = new FeishuProperties();
        properties.setRepoAliases(Map.of("hmdp", ""));
        FeishuRepoResolver resolver = new FeishuRepoResolver(properties);

        assertTrue(resolver.resolveRepoId("hmdp").isEmpty());
        assertTrue(resolver.resolveRepoId("unknown").isEmpty());
    }
}
