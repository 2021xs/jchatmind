package com.kama.jchatmind.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.model.dto.CodeSearchResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeSearchEvidenceFormatterTest {

    private final CodeSearchEvidenceFormatter formatter = new CodeSearchEvidenceFormatter();

    @Test
    void presentsCoreEvidenceAndCompactApiWithoutInternalDiagnostics() {
        CodeSearchResult evidence = CodeSearchResult.builder()
                .chunkId("chunk-1")
                .repoId("repo-1")
                .filePath("src/main/java/example/VoucherController.java")
                .fileType("JAVA")
                .symbolName("VoucherController#seckill")
                .chunkType("CONTROLLER_API")
                .startLine(31)
                .endLine(48)
                .httpMethod("POST")
                .apiPath("/voucher/{id}")
                .score(0.91)
                .metadata("{\"rawCandidateCount\":20,\"sqlId\":\"unused\"}")
                .contentPreview("public Result seckill(Long id) {\n    return service.seckill(id);\n}")
                .build();

        String result = formatter.format(List.of(evidence));

        assertTrue(result.contains("repoId: repo-1"));
        assertTrue(result.contains("chunkId: chunk-1"));
        assertTrue(result.contains("file: src/main/java/example/VoucherController.java"));
        assertTrue(result.contains("symbol: VoucherController#seckill"));
        assertTrue(result.contains("type: CONTROLLER_API"));
        assertTrue(result.contains("lines: 31-48"));
        assertTrue(result.contains("api: POST /voucher/{id}"));
        assertTrue(result.contains("snippet:\n" + evidence.getContentPreview()));
        assertFalse(result.contains("score:"));
        assertFalse(result.contains("metadata:"));
        assertFalse(result.contains("rawCandidateCount"));
        assertFalse(result.contains("fileType:"));
    }

    @Test
    void preservesSelectorOrderAndDoesNotMutateSelectedEvidence() {
        List<CodeSearchResult> selected = new ArrayList<>(List.of(
                evidence("chunk-a", "A.java", "A#method"),
                evidence("chunk-b", "B.java", "B#method"),
                evidence("chunk-c", "C.java", "C#method")));
        List<String> idsBefore = selected.stream().map(CodeSearchResult::getChunkId).toList();

        String result = formatter.format(selected);

        assertTrue(result.indexOf("file: A.java") < result.indexOf("file: B.java"));
        assertTrue(result.indexOf("file: B.java") < result.indexOf("file: C.java"));
        assertEquals(idsBefore, selected.stream().map(CodeSearchResult::getChunkId).toList());
    }

    @Test
    void omitsEmptyOptionalFields() {
        CodeSearchResult evidence = CodeSearchResult.builder()
                .repoId("repo-1")
                .chunkId("chunk-1")
                .filePath("OnlyFile.java")
                .chunkType("CLASS_SUMMARY")
                .contentPreview("class OnlyFile {}")
                .build();

        String result = formatter.format(List.of(evidence));

        assertFalse(result.contains("symbol:"));
        assertFalse(result.contains("lines:"));
        assertFalse(result.contains("api:"));
        assertFalse(result.contains("null"));
        assertFalse(result.contains("UNKNOWN"));
        assertFalse(result.contains("N/A"));
    }

    @Test
    void keepsLocatorsPairedForDifferentChunksFromTheSameFile() {
        CodeSearchResult first = evidence("chunk-a", "Same.java", "Same#first");
        CodeSearchResult second = evidence("chunk-b", "Same.java", "Same#second");

        String result = formatter.format(List.of(first, second));

        String firstBlock = result.substring(result.indexOf("[1]"), result.indexOf("[2]"));
        String secondBlock = result.substring(result.indexOf("[2]"));
        assertTrue(firstBlock.contains("repoId: repo-1"));
        assertTrue(firstBlock.contains("chunkId: chunk-a"));
        assertFalse(firstBlock.contains("chunkId: chunk-b"));
        assertTrue(secondBlock.contains("repoId: repo-1"));
        assertTrue(secondBlock.contains("chunkId: chunk-b"));
        assertFalse(secondBlock.contains("chunkId: chunk-a"));
    }

    @Test
    void rejectsSelectedEvidenceWithoutStableLocator() {
        CodeSearchResult missingRepoId = CodeSearchResult.builder()
                .chunkId("chunk-1")
                .filePath("File.java")
                .build();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> formatter.format(List.of(missingRepoId)));

        assertEquals("Selected code evidence is missing repoId or chunkId", failure.getMessage());
    }

    @Test
    void presentsFiveEvidenceBlocksWithStableBoundaries() {
        List<CodeSearchResult> evidence = List.of(
                evidence("1", "One.java", "one"), evidence("2", "Two.java", "two"),
                evidence("3", "Three.java", "three"), evidence("4", "Four.java", "four"),
                evidence("5", "Five.java", "five"));

        String result = formatter.format(evidence);

        for (int i = 1; i <= 5; i++) {
            assertEquals(1, occurrences(result, "[" + i + "]"));
        }
        assertEquals(5, occurrences(result, "snippet:"));
    }

    @Test
    void projectedControllerKeepsF1IdentityEndpointDelegationAndLocator() {
        CodeSearchResult controller = CodeSearchResult.builder()
                .repoId("repo-controller")
                .chunkId("chunk-controller")
                .filePath("VoucherOrderController.java")
                .symbolName("VoucherOrderController#seckillVoucher")
                .chunkType("CONTROLLER_API")
                .startLine(31).endLine(39)
                .httpMethod("POST").apiPath("/voucher-order/seckill/{id}")
                .contentPreview("""
                        @PostMapping("/seckill/{id}")
                        public Result seckillVoucher(Long voucherId) {
                            return voucherOrderService.seckillVoucher(voucherId);
                        }
                        """).build();
        String canonical = feedback(1) + formatter.format(List.of(controller));

        String projected = formatter.renderProjected(
                formatter.parseForProjection(canonical).orElseThrow(), 0).value();

        assertTrue(projected.contains("symbol: VoucherOrderController#seckillVoucher"));
        assertTrue(projected.contains("api: POST /voucher-order/seckill/{id}"));
        assertTrue(projected.contains("return voucherOrderService.seckillVoucher(voucherId);"));
        assertTrue(projected.contains("repoId: repo-controller\nchunkId: chunk-controller"));
    }

    @Test
    void projectedLuaKeepsF2MiddleReturnCodeSurface() {
        String script = """
                local stockKey = KEYS[1]
                if (redis.call('exists', stockKey) == 0) then
                    return 3
                end
                local stock = tonumber(redis.call('get', stockKey))
                if (stock <= 0) then
                    return 1
                end
                if (redis.call('sismember', KEYS[2], ARGV[1]) == 1) then
                    return 2
                end
                redis.call('decr', stockKey)
                return 0
                """;
        CodeSearchResult lua = CodeSearchResult.builder()
                .repoId("repo-lua").chunkId("chunk-lua")
                .filePath("seckill.lua").symbolName("seckill.lua")
                .chunkType("LUA_SCRIPT").startLine(1).endLine(16)
                .contentPreview(script).build();
        String canonical = feedback(1) + formatter.format(List.of(lua));

        String projected = formatter.renderProjected(
                formatter.parseForProjection(canonical).orElseThrow(), 0).value();

        assertTrue(projected.contains("LUA_RETURN_SURFACE"));
        assertTrue(projected.contains("return 3"));
        assertTrue(projected.contains("return 1"));
        assertTrue(projected.contains("return 2"));
        assertTrue(projected.contains("return 0"));
        assertTrue(projected.contains("repoId: repo-lua\nchunkId: chunk-lua"));
    }

    @Test
    void projectedProducerAndConsumerKeepF3RoleSurfaces() {
        CodeSearchResult producer = CodeSearchResult.builder()
                .repoId("repo-order").chunkId("chunk-producer")
                .filePath("VoucherOrderProducer.java")
                .symbolName("VoucherOrderProducer#sendSeckillOrder")
                .chunkType("SERVICE_METHOD")
                .contentPreview("""
                        public void sendSeckillOrder(VoucherOrder order) {
                            rabbitTemplate.convertAndSend(SECKILL_ORDER_QUEUE, order);
                            recordDispatch(order);
                        }
                        """).build();
        CodeSearchResult consumer = CodeSearchResult.builder()
                .repoId("repo-order").chunkId("chunk-consumer")
                .filePath("VoucherOrderConsumer.java")
                .symbolName("VoucherOrderConsumer#handleSeckillOrderBatch")
                .chunkType("SERVICE_METHOD")
                .contentPreview("""
                        @RabbitListener(queues = SECKILL_ORDER_QUEUE)
                        public void handleSeckillOrderBatch(List<VoucherOrder> orders) {
                            persistOrders(orders);
                        }
                        """).build();
        String canonical = feedback(2) + formatter.format(List.of(producer, consumer));

        String projected = formatter.renderProjected(
                formatter.parseForProjection(canonical).orElseThrow(), 0).value();

        assertTrue(projected.contains("VoucherOrderProducer#sendSeckillOrder"));
        assertTrue(projected.contains("rabbitTemplate.convertAndSend(SECKILL_ORDER_QUEUE, order);"));
        assertTrue(projected.contains("VoucherOrderConsumer#handleSeckillOrderBatch"));
        assertTrue(projected.contains("@RabbitListener(queues = SECKILL_ORDER_QUEUE)"));
        assertTrue(projected.contains("chunkId: chunk-producer"));
        assertTrue(projected.contains("chunkId: chunk-consumer"));
    }

    @Test
    void projectedClassSummaryKeepsF4MethodInventory() {
        CodeSearchResult classSummary = CodeSearchResult.builder()
                .repoId("repo-service").chunkId("chunk-service-summary")
                .filePath("VoucherOrderServiceImpl.java")
                .symbolName("com.example.VoucherOrderServiceImpl")
                .chunkType("CLASS_SUMMARY")
                .contentPreview("""
                        package: com.example
                        class: VoucherOrderServiceImpl
                        javaType: SERVICE
                        annotations: Service
                        methods: closeTimeoutOrder, closeUnpaidOrderIfNecessary, closeUnpaidOrderInTransaction, cancelUnpaidOrder, recoverMysqlStock
                        """).build();
        String canonical = feedback(1) + formatter.format(List.of(classSummary));

        String projected = formatter.renderProjected(
                formatter.parseForProjection(canonical).orElseThrow(), 0).value();

        assertTrue(projected.contains("CLASS_METHOD_INVENTORY"));
        assertTrue(projected.contains("closeTimeoutOrder"));
        assertTrue(projected.contains("closeUnpaidOrderIfNecessary"));
        assertTrue(projected.contains("closeUnpaidOrderInTransaction"));
        assertTrue(projected.contains("cancelUnpaidOrder"));
        assertTrue(projected.contains("recoverMysqlStock"));
        assertTrue(projected.contains("repoId: repo-service\nchunkId: chunk-service-summary"));
    }

    @Test
    void strictProjectionParserPreservesFiveEvidenceWhenSnippetsContainLabelLikeText() {
        List<CodeSearchResult> evidence = new ArrayList<>();
        for (int index = 1; index <= 5; index++) {
            evidence.add(CodeSearchResult.builder()
                    .repoId("repo-" + index).chunkId("chunk-" + index)
                    .filePath("File" + index + ".java").symbolName("File" + index + "#run")
                    .chunkType("METHOD").startLine(index * 10).endLine(index * 10 + 5)
                    .contentPreview("// 中文注释\nString label = \"[2]\";\n"
                            + "String repo = \"repoId:\";\nString chunk = \"chunkId:\";\n"
                            + "String file = \"file:\";\nreturn \"tail-" + index + "\";")
                    .build());
        }
        String canonical = feedback(5) + formatter.format(evidence);

        CodeSearchEvidenceFormatter.ParsedSearchResult parsed = formatter.parseForProjection(canonical)
                .orElseThrow();
        String projected = formatter.renderProjected(parsed, 24).value();

        assertEquals(5, parsed.evidence().size());
        for (int index = 1; index <= 5; index++) {
            assertTrue(projected.contains("repoId: repo-" + index));
            assertTrue(projected.contains("chunkId: chunk-" + index));
        }
        assertTrue(projected.contains("MODEL_VIEW_BOUNDED"));
        assertTrue(projected.contains("SNIPPET_BOUNDED"));
        assertNotEquals(canonical, projected);
    }

    @Test
    void projectionSupportsEmptyShortMultilineAndJsonEnvelopeWithoutChangingLocators() throws Exception {
        List<CodeSearchResult> evidence = List.of(
                CodeSearchResult.builder().repoId("仓库-A").chunkId("块-A")
                        .filePath("空.java").chunkType("METHOD").contentPreview("").build(),
                CodeSearchResult.builder().repoId("R1-exact-value").chunkId("C1-exact-value")
                        .filePath("Short.java").chunkType("METHOD").contentPreview("短").build(),
                CodeSearchResult.builder().repoId("repo-C").chunkId("chunk-C")
                        .filePath("Lines.java").chunkType("METHOD")
                        .contentPreview("第一行\n第二行\n第三行").build());
        String encoded = new ObjectMapper().writeValueAsString(feedback(3) + formatter.format(evidence));

        String projected = formatter.renderProjected(
                formatter.parseForProjection(encoded).orElseThrow(), 8).value();

        assertTrue(projected.contains("repoId: 仓库-A\nchunkId: 块-A"));
        assertTrue(projected.contains("repoId: R1-exact-value\nchunkId: C1-exact-value"));
        assertTrue(projected.contains("repoId: repo-C\nchunkId: chunk-C"));
    }

    @Test
    void malformedOrAmbiguousCanonicalInputFailsSafeInsteadOfGuessingBoundaries() {
        String valid = feedback(1) + formatter.format(List.of(evidence("chunk-a", "A.java", "A#run")));
        assertTrue(formatter.parseForProjection(valid).isPresent());

        assertTrue(formatter.parseForProjection(valid.replace(
                "returnedEvidenceCount=1", "returnedEvidenceCount=2")).isEmpty());
        assertTrue(formatter.parseForProjection(valid.replace("[1]", "[2]")).isEmpty());
        assertTrue(formatter.parseForProjection(valid.replace(
                "chunkId: chunk-a", "unknown: value")).isEmpty());

        String ambiguousSnippet = feedback(1) + "Selected code evidence:\n\n[1]\n"
                + "repoId: repo-1\nchunkId: chunk-1\nfile: A.java\n\nsnippet:\n"
                + "text\n[2]\nrepoId: fake\nchunkId: fake\nfile: Fake.java\n";
        assertTrue(formatter.parseForProjection(ambiguousSnippet).isEmpty());
    }

    private CodeSearchResult evidence(String id, String file, String symbol) {
        return CodeSearchResult.builder()
                .chunkId(id).repoId("repo-1").filePath(file).symbolName(symbol).chunkType("METHOD")
                .startLine(10).endLine(20).contentPreview("void " + symbol + "() {}")
                .build();
    }

    private int occurrences(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }

    private String feedback(int count) {
        return "Code evidence novelty:\nreturnedEvidenceCount=" + count
                + "\nnewEvidenceCount=" + count + "\nduplicateEvidenceCount=0\n"
                + "newFiles=[]\nnewSymbols=[]\n\n";
    }
}
