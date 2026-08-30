package com.kama.jchatmind.benchmark.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextLifecycleBenchmarkSuiteTest {

    @Test
    void suiteIsVersionedBalancedAndBoundToFixedRepositorySnapshot() throws Exception {
        ContextLifecycleBenchmarkSuite suite = new ObjectMapper().readValue(
                new ClassPathResource("benchmark/context_lifecycle_benchmark_suite.json").getInputStream(),
                ContextLifecycleBenchmarkSuite.class);

        suite.validate();
        Map<String, Long> counts = suite.cases.stream()
                .collect(Collectors.groupingBy(value -> value.category, Collectors.counting()));

        assertEquals("context-lifecycle-v1", suite.benchmarkSuiteVersion);
        assertEquals("LEGACY", suite.architectureLabel);
        assertEquals(26, suite.cases.size());
        assertEquals(6L, counts.get("D_CROSS_TASK_POLLUTION"));
        assertEquals(2L, suite.cases.stream().filter(value -> !value.active()).count());
        assertTrue(suite.repositorySnapshot.chunkManifestMd5.matches("[0-9a-f]{32}"));
    }

    @Test
    void frozenSuiteAndProfileRetainExactGitBlobIdentity() throws Exception {
        assertEquals("82f65923f56fd9bbb19d5e68db16f874cf58c11a",
                gitBlobSha1(new ClassPathResource("benchmark/context_lifecycle_benchmark_suite.json")));
        assertEquals("2562cc2c295876c75a962cb362776749a2d1ce95",
                gitBlobSha1(new ClassPathResource("application-benchmark.yaml")));
    }

    private String gitBlobSha1(ClassPathResource resource) throws Exception {
        byte[] content;
        try (var input = resource.getInputStream()) {
            content = input.readAllBytes();
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + content.length + '\0').getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest(content));
    }
}
