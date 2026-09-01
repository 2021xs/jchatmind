package com.kama.jchatmind.benchmark.compression;

import java.util.ArrayList;
import java.util.List;

final class CompressionReplayScenarios {
    static final String REPO_ID = "bf4ef891-330b-4ce8-9002-ba4c43ffe210";
    static final String PRESSURE_RUN = "context-lifecycle-2026-09-01T08-18-00.918463+08-00-caa3b232";
    static final String PRESSURE_USER = "Produce an evidence-backed architecture walkthrough of FlashDeal seckill "
            + "covering API, Lua return codes, reservation metadata, MQ constants/config, producer, consumer, "
            + "transactional persistence, duplicate defense, timeout close, and stock recovery. Inspect each "
            + "component before answering.";

    private CompressionReplayScenarios() {
    }

    static List<Scenario> all() {
        return List.of(
                initialState(),
                exactValues(),
                relationship(),
                multipleStableRefs(),
                noOp(),
                existingRichState(),
                nearBudget(),
                realPressure());
    }

    private static Scenario initialState() {
        List<Call> calls = List.of(
                call("s1-search-1", "searchProjectCode",
                        "{\"repoId\":\"" + REPO_ID + "\",\"query\":\"seckill flash sale API controller endpoint Lua return codes reservation metadata Redis stock user duplicate order\"}"),
                call("s1-search-2", "searchProjectCode",
                        "{\"repoId\":\"" + REPO_ID + "\",\"query\":\"MQ constants configuration RocketMQ RabbitMQ seckill order producer consumer timeout close stock recovery\"}"),
                call("s1-search-3", "searchProjectCode",
                        "{\"repoId\":\"" + REPO_ID + "\",\"query\":\"transactional persistence seckill order create save duplicate defense idempotent consumer stock recovery close timeout\"}"));
        List<Response> responses = List.of(
                response("s1-search-1", "searchProjectCode", SEARCH_API_LUA, false),
                response("s1-search-2", "searchProjectCode", SEARCH_MQ_CONFIG, false),
                response("s1-search-3", "searchProjectCode", SEARCH_PERSISTENCE, false));
        return scenario(
                "S1_INITIAL_STATE", "INITIAL_STATE", PRESSURE_USER, null, 0,
                3_500, 5_000, fixedPlanning(1_200), List.of(new Group("search-batch", calls, responses)),
                List.of("VoucherOrderController#seckillVoucher", "seckill.lua", "RabbitMqConfig"),
                List.of(),
                List.of("8edc54bf-73db-4be0-8ba6-f0145a714815",
                        "450de626-32e8-4eab-a81e-7012c48b33db",
                        "2aa9220e-3a91-4c11-bda9-fa3522af2841"),
                List.of(), List.of(), List.of(), false, true,
                PRESSURE_RUN + ":compression:1");
    }

    private static Scenario exactValues() {
        return scenario(
                "S2_EXACT_VALUES", "EXACT_VALUES", PRESSURE_USER, BASE_STATE, 1,
                3_500, 100, fixedPlanning(420),
                withCovered(1, group("exact-values",
                        exactCall("s2-lua", "450de626-32e8-4eab-a81e-7012c48b33db"),
                        exactResponse("s2-lua", SECKILL_LUA),
                        exactCall("s2-queue", "2aa9220e-3a91-4c11-bda9-fa3522af2841"),
                        exactResponse("s2-queue", TIMEOUT_DELAY_QUEUE))),
                List.of("seckill:pending", "ORDER_CLOSE_EXCHANGE"),
                List.of(
                        returnCodeAssertion("success-code", 0,
                                "success(?:ful)?|qualification passed|checks pass|record(?:ed|ing)(?: the)? pending reservation|pending reservation (?:was )?recorded"),
                        returnCodeAssertion("stock-code", 1,
                                "insufficient stock|stock is not enough|out-of-stock|stock <= 0"),
                        returnCodeAssertion("duplicate-code", 2,
                                "duplicate(?: order| user)?|already (?:ordered|in .*order)"),
                        returnCodeAssertion("missing-code", 3,
                                "(?:stock key|seckill:stock).{0,80}(?:missing|absent|does not exist|not set)|missing stock key"),
                        assertion("ttl", "orderTimeoutSeconds * 1000", "ordertimeseconds * 1000")),
                List.of("450de626-32e8-4eab-a81e-7012c48b33db",
                        "2aa9220e-3a91-4c11-bda9-fa3522af2841"),
                List.of(), protectedBase(), List.of(), false, true, "derived from " + PRESSURE_RUN);
    }

    private static Scenario relationship() {
        return scenario(
                "S3_RELATIONSHIP", "RELATIONSHIP",
                "Explain the concrete producer -> queue -> consumer relationship for FlashDeal seckill orders.",
                null, 0, 3_500, 100, fixedPlanning(360),
                List.of(group("producer-consumer",
                        exactCall("s3-producer", "2b2ba849-d84c-453b-b23a-2aa8c0dd7d90"),
                        exactResponse("s3-producer", PRODUCER),
                        exactCall("s3-constants", "13b800f8-8acf-49f6-a840-44b058154de3"),
                        exactResponse("s3-constants", RABBIT_CONSTANTS),
                        exactCall("s3-consumer", "e73a197a-ec27-4de3-aa82-a8a7a06864ef"),
                        exactResponse("s3-consumer", CONSUMER))),
                List.of("VoucherOrderProducer#sendSeckillOrder", "VoucherOrderConsumer#handleSeckillOrderBatch"),
                List.of(),
                List.of("2b2ba849-d84c-453b-b23a-2aa8c0dd7d90",
                        "13b800f8-8acf-49f6-a840-44b058154de3",
                        "e73a197a-ec27-4de3-aa82-a8a7a06864ef"),
                List.of(new RelationshipAssertion("producer-queue-consumer",
                        List.of("VoucherOrderProducer", "SECKILL_ORDER_QUEUE", "VoucherOrderConsumer"),
                        List.of("->", "queue", "publish", "send", "consume", "listen"))),
                List.of(), List.of(), false, true,
                "canonical chunks from context-lifecycle-2026-08-31T23-54-24.080973800+08-00-16c367ac");
    }

    private static Scenario multipleStableRefs() {
        return scenario(
                "S4_MULTIPLE_STABLE_REFS", "STABLE_REFS",
                "Retain stable locators for the producer, Rabbit constants, and batch consumer evidence.",
                null, 0, 3_500, 100, fixedPlanning(320),
                List.of(group("three-refs",
                        exactCall("s4-producer", "2b2ba849-d84c-453b-b23a-2aa8c0dd7d90"),
                        exactResponse("s4-producer", PRODUCER),
                        exactCall("s4-constants", "13b800f8-8acf-49f6-a840-44b058154de3"),
                        exactResponse("s4-constants", RABBIT_CONSTANTS),
                        exactCall("s4-consumer", "e73a197a-ec27-4de3-aa82-a8a7a06864ef"),
                        exactResponse("s4-consumer", CONSUMER))),
                List.of("VoucherOrderProducer", "RabbitConstants", "VoucherOrderConsumer"),
                List.of(),
                List.of(REPO_ID,
                        "2b2ba849-d84c-453b-b23a-2aa8c0dd7d90",
                        "13b800f8-8acf-49f6-a840-44b058154de3",
                        "e73a197a-ec27-4de3-aa82-a8a7a06864ef"),
                List.of(), List.of(), List.of(), false, true,
                "canonical chunks from context-lifecycle-2026-08-31T23-54-24.080973800+08-00-16c367ac");
    }

    private static Scenario noOp() {
        return scenario(
                "S5_NO_OP", "NO_OP", PRESSURE_USER, BASE_STATE, 1,
                3_500, 100, fixedPlanning(320),
                withCovered(1, group("duplicate-controller",
                        exactCall("s5-controller", "8edc54bf-73db-4be0-8ba6-f0145a714815"),
                        exactResponse("s5-controller", CONTROLLER))),
                List.of(), List.of(), List.of(), List.of(), protectedBase(), List.of(), true, false,
                "duplicate canonical chunk already represented in accepted State");
    }

    private static Scenario existingRichState() {
        return scenario(
                "S6_EXISTING_RICH_STATE", "EXISTING_RICH_STATE", PRESSURE_USER, BASE_STATE, 1,
                3_500, 100, fixedPlanning(420),
                withCovered(1, group("listener-detail",
                        exactCall("s6-listener", "57467053-facd-4dea-8118-31abf434ebcd"),
                        exactResponse("s6-listener", LISTENER_FACTORY))),
                List.of("AcknowledgeMode.MANUAL", "setDefaultRequeueRejected(false)", "rabbitRetryInterceptor"),
                List.of(assertion("manual-ack", "AcknowledgeMode.MANUAL", "manual acknowledgement", "manual ack")),
                List.of("57467053-facd-4dea-8118-31abf434ebcd"),
                List.of(), protectedBase(), List.of(), false, true,
                "derived from " + PRESSURE_RUN);
    }

    private static Scenario nearBudget() {
        String state = nearBudgetState();
        return scenario(
                "S7_NEAR_BUDGET", "NEAR_BUDGET",
                "Preserve the established timeout investigation state and add only the newly confirmed stale-row recovery.",
                state, 4, 3_500, 100, fixedPlanning(760),
                withCovered(4, group("stale-recovery",
                        exactCall("s7-recovery", "ac4dfed8-1621-4d54-88ba-e0844b74c640"),
                        exactResponse("s7-recovery", RECOVERY_SQL))),
                List.of("PROCESSING", "INIT", "ORDER_CLOSE_RETRY_PROCESSING_TIMEOUT"),
                List.of(assertion("status-transition", "PROCESSING", "INIT")),
                List.of("ac4dfed8-1621-4d54-88ba-e0844b74c640"),
                List.of(),
                List.of("protected-near-budget-fact-01", "protected-near-budget-fact-24",
                        "protected-near-budget-constraint", "protected-near-budget-ref"),
                List.of(), false, true, "deterministic near-budget fixture using a real canonical SQL chunk");
    }

    private static Scenario realPressure() {
        return scenario(
                "S8_REAL_PRESSURE", "REAL_PRESSURE", PRESSURE_USER, BASE_STATE, 1,
                3_500, 5_000, fixedPlanning(990),
                withCovered(1, group("nine-exact-rereads",
                        exactCall("s8-controller", "8edc54bf-73db-4be0-8ba6-f0145a714815"),
                        exactResponse("s8-controller", CONTROLLER),
                        exactCall("s8-lua", "450de626-32e8-4eab-a81e-7012c48b33db"),
                        exactResponse("s8-lua", SECKILL_LUA),
                        exactCall("s8-delay", "2aa9220e-3a91-4c11-bda9-fa3522af2841"),
                        exactResponse("s8-delay", TIMEOUT_DELAY_QUEUE),
                        exactCall("s8-binding", "3cdb8c3a-26c6-4acd-af24-c36a26d1f0cd"),
                        exactResponse("s8-binding", TIMEOUT_BINDING),
                        exactCall("s8-listener", "57467053-facd-4dea-8118-31abf434ebcd"),
                        exactResponse("s8-listener", LISTENER_FACTORY),
                        exactCall("s8-create", "8a8a3f3b-578f-436b-92c1-ad9d7f0f0a89"),
                        exactResponse("s8-create", CREATE_ORDER),
                        exactCall("s8-recovery", "ac4dfed8-1621-4d54-88ba-e0844b74c640"),
                        exactResponse("s8-recovery", RECOVERY_SQL),
                        exactCall("s8-timeout", "609a18b5-9b39-4e8a-8350-c1d88fd58d3a"),
                        exactResponse("s8-timeout", PROCESSING_TIMEOUT),
                        exactCall("s8-rollback", "5803c9cf-129a-43bd-b3ca-fba7afe9fcf0"),
                        exactResponse("s8-rollback", ROLLBACK_LUA))),
                List.of("outboxEventService.saveOrderTimeoutEvents", "AcknowledgeMode.MANUAL",
                        "rollback", "seckill:pending"),
                List.of(
                        returnCodeAssertion("success-code", 0,
                                "success(?:ful)?|qualification passed|checks pass|record(?:ed|ing)(?: the)? pending reservation|pending reservation (?:was )?recorded"),
                        returnCodeAssertion("missing-code", 3,
                                "(?:stock key|seckill:stock).{0,80}(?:missing|absent|does not exist|not set)|missing stock key"),
                        regexAssertion("rollback-code",
                                "rollback.{0,300}(?:returns?|return code|code).*?`?3`?",
                                "(?:returns?|return code|code).*?`?3`?.{0,300}rollback"),
                        assertion("failure-reason", "ORDER_CLOSE_RETRY_PROCESSING_TIMEOUT")),
                List.of("450de626-32e8-4eab-a81e-7012c48b33db",
                        "8a8a3f3b-578f-436b-92c1-ad9d7f0f0a89",
                        "5803c9cf-129a-43bd-b3ca-fba7afe9fcf0"),
                List.of(), protectedBase(), List.of(), false, true,
                PRESSURE_RUN + ":compression:2; fixed planning token pressure reconstructed at 990 estimated tokens");
    }

    private static Scenario scenario(String id,
                                     String category,
                                     String user,
                                     String existingState,
                                     int coveredThrough,
                                     int hardBudget,
                                     int maxSingleToolTokens,
                                     List<String> fixedPlanning,
                                     List<Group> groups,
                                     List<String> mustContainKnown,
                                     List<TextAssertion> exactValues,
                                     List<String> refs,
                                     List<RelationshipAssertion> relationships,
                                     List<String> protectedState,
                                     List<String> mustNotContain,
                                     boolean expectedNoOp,
                                     boolean expectNovelty,
                                     String provenance) {
        return new Scenario(id, category, user, existingState, coveredThrough, groups,
                groups.stream().skip(coveredThrough).map(Group::groupId).toList(), hardBudget, maxSingleToolTokens,
                fixedPlanning, mustContainKnown, exactValues, refs, relationships,
                protectedState, mustNotContain, expectedNoOp, expectNovelty, provenance);
    }

    private static Group group(String id, Object... callResponsePairs) {
        List<Call> calls = new ArrayList<>();
        List<Response> responses = new ArrayList<>();
        for (Object item : callResponsePairs) {
            if (item instanceof Call call) {
                calls.add(call);
            } else if (item instanceof Response response) {
                responses.add(response);
            } else {
                throw new IllegalArgumentException("Unsupported group item: " + item);
            }
        }
        return new Group(id, List.copyOf(calls), List.copyOf(responses));
    }

    private static List<Group> withCovered(int count, Group uncovered) {
        List<Group> groups = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            String id = "covered-" + index;
            groups.add(group(id,
                    call(id, "searchProjectCode", "{\"query\":\"already covered\"}"),
                    response(id, "searchProjectCode", "already covered by Existing Accepted State", false)));
        }
        groups.add(uncovered);
        return List.copyOf(groups);
    }

    private static Call exactCall(String id, String chunkId) {
        return call(id, "getCodeChunk",
                "{\"repoId\":\"" + REPO_ID + "\",\"chunkId\":\"" + chunkId + "\"}");
    }

    private static Response exactResponse(String id, String body) {
        return response(id, "getCodeChunk", body, true);
    }

    private static Call call(String id, String toolName, String arguments) {
        return new Call("call-" + id, toolName, arguments);
    }

    private static Response response(String id, String toolName, String body, boolean jsonEnvelope) {
        return new Response("call-" + id, toolName, body, jsonEnvelope);
    }

    private static TextAssertion assertion(String id, String... anyOf) {
        return new TextAssertion(id, List.of(anyOf), false);
    }

    private static TextAssertion regexAssertion(String id, String... patterns) {
        return new TextAssertion(id, List.of(patterns), true);
    }

    private static TextAssertion returnCodeAssertion(String id, int code, String meaningPattern) {
        String codePattern = "`?" + code + "`?";
        return regexAssertion(id,
                "(?:return code|returns?|code)?\\s*" + codePattern
                        + ".{0,300}(?:means?|=|is|on|if)?\\s*(?:" + meaningPattern + ")",
                "(?:" + meaningPattern + ").{0,300}(?:return code|returns?|code)?\\s*" + codePattern);
    }

    private static List<String> protectedBase() {
        return List.of(
                "VoucherOrderController#seckillVoucher` exposes `POST /voucher-order/seckill/{id}",
                "VoucherOrderServiceImpl#createVoucherOrder` is annotated with `@Transactional",
                "bf4ef891-330b-4ce8-9002-ba4c43ffe210/450de626-32e8-4eab-a81e-7012c48b33db");
    }

    private static List<String> fixedPlanning(int estimatedTokens) {
        int chars = estimatedTokens * 3;
        String marker = "FROZEN_PLANNING_MATERIAL ";
        return List.of(marker.repeat(Math.max(1, chars / marker.length())));
    }

    private static String nearBudgetState() {
        StringBuilder out = new StringBuilder("""
                Current Task Continuation State

                - Goal
                  - Preserve a detailed timeout-order investigation.
                - Known
                """);
        for (int index = 1; index <= 24; index++) {
            out.append("  - protected-near-budget-fact-")
                    .append(String.format("%02d", index))
                    .append(" retains a confirmed timeout workflow relationship and evidence marker ")
                    .append(index).append(".\n");
        }
        out.append("""
                - Constraints
                  - protected-near-budget-constraint = exact-value-314159.
                - Refs
                  - protected-near-budget-ref = bf4ef891-330b-4ce8-9002-ba4c43ffe210/609a18b5-9b39-4e8a-8350-c1d88fd58d3a.
                - Open
                  - Confirm stale PROCESSING recovery behavior.
                - Next
                  - Inspect the recovery SQL.
                """);
        return out.toString().strip();
    }

    record Scenario(
            String scenarioId,
            String category,
            String originalUser,
            String existingState,
            int coveredThroughLogicalGroupBefore,
            List<Group> groups,
            List<String> selectedGroupIdentities,
            int hardContextBudget,
            int maxSingleToolResultTokens,
            List<String> fixedPlanningMaterial,
            List<String> mustContainKnown,
            List<TextAssertion> exactValueAssertions,
            List<String> mustContainRefs,
            List<RelationshipAssertion> relationshipAssertions,
            List<String> mustPreserveExisting,
            List<String> mustNotContain,
            boolean expectedNoOp,
            boolean expectNovelty,
            String provenance) {
    }

    record Group(String groupId, List<Call> calls, List<Response> responses) {
    }

    record Call(String callId, String toolName, String arguments) {
    }

    record Response(String callId, String toolName, String body, boolean jsonEnvelope) {
    }

    record TextAssertion(String assertionId, List<String> anyOf, boolean regex) {
    }

    record RelationshipAssertion(String assertionId, List<String> allOf, List<String> linkAnyOf) {
    }

    private static final String BASE_STATE = """
            Current Task Continuation State

            - Goal
              - Complete the original current User question retained separately in raw form.
            - Known
              - `VoucherOrderController#seckillVoucher` exposes `POST /voucher-order/seckill/{id}`.
              - `VoucherOrderController#seckillVoucher` is annotated with `@RateLimit(key = "seckill:voucher", windowSeconds = 5, maxRequests = 3, dimension = RateLimitDimension.USER)`.
              - `VoucherOrderController#seckillVoucher` delegates to `voucherOrderService.seckillVoucher(voucherId)`.
              - `seckill.lua` has three observed return codes: `1`, `2`, and `3`.
              - `RabbitMqConfig` defines `orderTimeoutDelayQueue()` and creates it as a durable queue with arguments map.
              - `RabbitMqConfig#orderTimeoutBinding` binds `orderTimeoutDelayQueue` to `orderTimeoutExchange` with `ORDER_TIMEOUT_ROUTING_KEY`.
              - `RabbitMqConfig` defines `rabbitListenerContainerFactory(...)` and wires `ConnectionFactory`, `MessageConverter`, and `@Qualifier("rabbitRetryInterceptor") RetryOperationsInterceptor`.
              - `VoucherOrderServiceImpl#createVoucherOrder` is annotated with `@Transactional`.
              - `SeckillReservationState#isProcessingTimedOut(long nowMillis, long processingTimeoutMillis)` returns true when `status == SeckillReservationStatus.PROCESSING` and elapsed time meets `Math.max(0L, processingTimeoutMillis)`.
              - `OrderTimeoutCloseFailMapper.xml` contains a `recoverStuckProcessing` SQL mapping.
              - `seckill_rollback.lua` exists as a rollback Lua script.
            - Constraints
              - none
            - Refs
              - bf4ef891-330b-4ce8-9002-ba4c43ffe210/8edc54bf-73db-4be0-8ba6-f0145a714815
              - bf4ef891-330b-4ce8-9002-ba4c43ffe210/450de626-32e8-4eab-a81e-7012c48b33db
              - bf4ef891-330b-4ce8-9002-ba4c43ffe210/2aa9220e-3a91-4c11-bda9-fa3522af2841
              - bf4ef891-330b-4ce8-9002-ba4c43ffe210/3cdb8c3a-26c6-4acd-af24-c36a26d1f0cd
              - bf4ef891-330b-4ce8-9002-ba4c43ffe210/57467053-facd-4dea-8118-31abf434ebcd
              - bf4ef891-330b-4ce8-9002-ba4c43ffe210/8a8a3f3b-578f-436b-92c1-ad9d7f0f0a89
              - bf4ef891-330b-4ce8-9002-ba4c43ffe210/ac4dfed8-1621-4d54-88ba-e0844b74c640
              - bf4ef891-330b-4ce8-9002-ba4c43ffe210/609a18b5-9b39-4e8a-8350-c1d88fd58d3a
              - bf4ef891-330b-4ce8-9002-ba4c43ffe210/5803c9cf-129a-43bd-b3ca-fba7afe9fcf0
            - Open
              - Need exact `seckill.lua` return-code meanings and the reservation metadata keys/fields to complete the evidence-backed walkthrough.
              - Need exact `RabbitConstants` / `RedisConstants` members to document MQ and reservation constants precisely.
              - Need the consumer method that receives the timeout-close message and the stock-recovery path details.
            - Next
              - Inspect the remaining Lua, Redis constants, MQ consumer, and mapper/service implementation details for exact codes, keys, and recovery flow.
            """.strip();

    private static final String SEARCH_API_LUA = """
            returnedEvidenceCount=5
            newEvidenceCount=5
            duplicateEvidenceCount=0

            MODEL_VIEW_BOUNDED: Some snippet detail was omitted from model view; every selected evidence header and semantic skeleton below is retained.
            Use getCodeChunk(repoId, chunkId) for exact source details when needed.

            Selected code evidence:

            [1]
            repoId: bf4ef891-330b-4ce8-9002-ba4c43ffe210
            chunkId: 450de626-32e8-4eab-a81e-7012c48b33db
            file: src/main/resources/seckill.lua
            symbol: seckill.lua
            type: LUA_SCRIPT
            lines: 1-47

            semanticSkeleton:
            LUA_RETURN_SURFACE:
            return 3
            return 1
            return 2

            snippet:
            [SNIPPET_OMITTED_FROM_MODEL_VIEW: originalChars=1215]

            [2]
            repoId: bf4ef891-330b-4ce8-9002-ba4c43ffe210
            chunkId: fd31eced-da2a-495d-9ab1-4fb87ca7f967
            file: src/main/java/com/flashdeal/utils/RedisConstants.java
            symbol: com.flashdeal.utils.RedisConstants
            type: CLASS_SUMMARY
            lines: 3-29

            semanticSkeleton:
            CLASS_METHOD_INVENTORY:
            methods:

            snippet:
            [SNIPPET_OMITTED_FROM_MODEL_VIEW: originalChars=95]

            [3]
            repoId: bf4ef891-330b-4ce8-9002-ba4c43ffe210
            chunkId: 8edc54bf-73db-4be0-8ba6-f0145a714815
            file: src/main/java/com/flashdeal/controller/VoucherOrderController.java
            symbol: com.flashdeal.controller.VoucherOrderController#seckillVoucher
            type: CONTROLLER_API
            lines: 31-35
            api: POST /voucher-order/seckill/{id}

            semanticSkeleton:
            METHOD_SURFACE:
            @PostMapping("seckill/{id}")
            @RateLimit(key = "seckill:voucher", windowSeconds = 5, maxRequests = 3, dimension = RateLimitDimension.USER)
            [METHOD_BODY_BOUNDED]
            return voucherOrderService.seckillVoucher(voucherId);

            snippet:
            [SNIPPET_OMITTED_FROM_MODEL_VIEW: originalChars=280]

            [4]
            repoId: bf4ef891-330b-4ce8-9002-ba4c43ffe210
            chunkId: 928cae7b-8384-4744-aac7-418ad9357eb7
            file: src/main/java/com/flashdeal/service/SeckillReservationService.java
            symbol: com.flashdeal.service.SeckillReservationService#classMembers
            type: JAVA_CLASS_MEMBER
            lines: 26-35

            snippet:
            [SNIPPET_OMITTED_FROM_MODEL_VIEW: originalChars=574]

            [5]
            repoId: bf4ef891-330b-4ce8-9002-ba4c43ffe210
            chunkId: 2775bba0-aa06-4b96-a328-fcc91322cae9
            file: src/main/java/com/flashdeal/utils/RedisConstants.java
            symbol: com.flashdeal.utils.RedisConstants#classMembers
            type: JAVA_CLASS_MEMBER
            lines: 4-28

            snippet:
            [SNIPPET_OMITTED_FROM_MODEL_VIEW: originalChars=1215]
            """.strip();

    private static final String SEARCH_MQ_CONFIG = """
            returnedEvidenceCount=5
            newEvidenceCount=5
            duplicateEvidenceCount=0

            MODEL_VIEW_BOUNDED: Some snippet detail was omitted from model view; every selected evidence header and semantic skeleton below is retained.
            Use getCodeChunk(repoId, chunkId) for exact source details when needed.

            Selected code evidence:

            [1]
            repoId: bf4ef891-330b-4ce8-9002-ba4c43ffe210
            chunkId: 13b800f8-8acf-49f6-a840-44b058154de3
            file: src/main/java/com/flashdeal/utils/RabbitConstants.java
            symbol: com.flashdeal.utils.RabbitConstants#classMembers
            type: JAVA_CLASS_MEMBER
            lines: 5-28

            snippet:
            [SNIPPET_OMITTED_FROM_MODEL_VIEW: originalChars=1215]

            [2]
            repoId: bf4ef891-330b-4ce8-9002-ba4c43ffe210
            chunkId: eabfbe55-64e6-4b5b-a329-916e7e920366
            file: src/main/java/com/flashdeal/config/RabbitMqConfig.java
            symbol: com.flashdeal.config.RabbitMqConfig#classMembers
            type: JAVA_CLASS_MEMBER
            lines: 50-78

            snippet:
            [SNIPPET_OMITTED_FROM_MODEL_VIEW: originalChars=979]

            [3]
            repoId: bf4ef891-330b-4ce8-9002-ba4c43ffe210
            chunkId: 3cdb8c3a-26c6-4acd-af24-c36a26d1f0cd
            file: src/main/java/com/flashdeal/config/RabbitMqConfig.java
            symbol: com.flashdeal.config.RabbitMqConfig#orderTimeoutBinding
            type: JAVA_METHOD
            lines: 174-181

            semanticSkeleton:
            METHOD_SURFACE:
            @Bean
                public Binding orderTimeoutBinding(
                        Queue orderTimeoutDelayQueue,
                        DirectExchange orderTimeoutExchange) {
            return BindingBuilder.bind(orderTimeoutDelayQueue)
            [METHOD_BODY_BOUNDED]
            .with(ORDER_TIMEOUT_ROUTING_KEY);

            snippet:
            [SNIPPET_OMITTED_FROM_MODEL_VIEW: originalChars=295]

            [4]
            repoId: bf4ef891-330b-4ce8-9002-ba4c43ffe210
            chunkId: 2aa9220e-3a91-4c11-bda9-fa3522af2841
            file: src/main/java/com/flashdeal/config/RabbitMqConfig.java
            symbol: com.flashdeal.config.RabbitMqConfig#orderTimeoutDelayQueue
            type: JAVA_METHOD
            lines: 165-172

            semanticSkeleton:
            METHOD_SURFACE:
            @Bean
                public Queue orderTimeoutDelayQueue() {
            Map<String, Object> args = new HashMap<>();
            [METHOD_BODY_BOUNDED]
            return new Queue(ORDER_TIMEOUT_DELAY_QUEUE, true, false, false, args);

            snippet:
            [SNIPPET_OMITTED_FROM_MODEL_VIEW: originalChars=387]

            [5]
            repoId: bf4ef891-330b-4ce8-9002-ba4c43ffe210
            chunkId: 57467053-facd-4dea-8118-31abf434ebcd
            file: src/main/java/com/flashdeal/config/RabbitMqConfig.java
            symbol: com.flashdeal.config.RabbitMqConfig#rabbitListenerContainerFactory
            type: JAVA_METHOD
            lines: 255-270

            semanticSkeleton:
            METHOD_SURFACE:
            @Bean
                public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
                        ConnectionFactory connectionFactory,
                        MessageConverter messageConverter,
                        @Qualifier("rabbitRetryInterceptor") RetryOperationsInterceptor rabbitRetryInterceptor) {
            SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
            [METHOD_BODY_BOUNDED]
            return factory;

            snippet:
            [SNIPPET_OMITTED_FROM_MODEL_VIEW: originalChars=918]
            """.strip();

    private static final String SEARCH_PERSISTENCE = """
            returnedEvidenceCount=5
            newEvidenceCount=4
            duplicateEvidenceCount=1

            MODEL_VIEW_BOUNDED: Some snippet detail was omitted from model view; every selected evidence header and semantic skeleton below is retained.
            Use getCodeChunk(repoId, chunkId) for exact source details when needed.

            Selected code evidence:

            [1]
            repoId: bf4ef891-330b-4ce8-9002-ba4c43ffe210
            chunkId: ac4dfed8-1621-4d54-88ba-e0844b74c640
            file: src/main/resources/mapper/OrderTimeoutCloseFailMapper.xml
            symbol: com.flashdeal.mapper.OrderTimeoutCloseFailMapper.recoverStuckProcessing
            type: MYBATIS_SQL
            lines: 69-78

            snippet:
            [SNIPPET_OMITTED_FROM_MODEL_VIEW: originalChars=459]

            [2]
            repoId: bf4ef891-330b-4ce8-9002-ba4c43ffe210
            chunkId: 8a8a3f3b-578f-436b-92c1-ad9d7f0f0a89
            file: src/main/java/com/flashdeal/service/impl/VoucherOrderServiceImpl.java
            symbol: com.flashdeal.service.impl.VoucherOrderServiceImpl#createVoucherOrder
            type: SERVICE_METHOD
            lines: 272-314

            semanticSkeleton:
            METHOD_SURFACE:
            @Transactional
                void createVoucherOrder(VoucherOrder voucherOrder) {
            Long userId = voucherOrder.getUserId();
            [METHOD_BODY_BOUNDED]
            ...[truncated]

            snippet:
            [SNIPPET_OMITTED_FROM_MODEL_VIEW: originalChars=1215]

            [3]
            repoId: bf4ef891-330b-4ce8-9002-ba4c43ffe210
            chunkId: 5803c9cf-129a-43bd-b3ca-fba7afe9fcf0
            file: src/main/resources/seckill_rollback.lua
            symbol: seckill_rollback.lua
            type: LUA_SCRIPT
            lines: 1-56

            semanticSkeleton:
            LUA_RETURN_SURFACE:
            return 1
            return 1

            snippet:
            [SNIPPET_OMITTED_FROM_MODEL_VIEW: originalChars=1215]

            [4]
            repoId: bf4ef891-330b-4ce8-9002-ba4c43ffe210
            chunkId: 450de626-32e8-4eab-a81e-7012c48b33db
            file: src/main/resources/seckill.lua
            symbol: seckill.lua
            type: LUA_SCRIPT
            lines: 1-47

            semanticSkeleton:
            LUA_RETURN_SURFACE:
            return 3
            return 1
            return 2

            snippet:
            [SNIPPET_OMITTED_FROM_MODEL_VIEW: originalChars=1215]

            [5]
            repoId: bf4ef891-330b-4ce8-9002-ba4c43ffe210
            chunkId: 609a18b5-9b39-4e8a-8350-c1d88fd58d3a
            file: src/main/java/com/flashdeal/dto/SeckillReservationState.java
            symbol: com.flashdeal.dto.SeckillReservationState#isProcessingTimedOut
            type: JAVA_METHOD
            lines: 16-20

            semanticSkeleton:
            METHOD_SURFACE:
            public boolean isProcessingTimedOut(long nowMillis, long processingTimeoutMillis) {
            return status == SeckillReservationStatus.PROCESSING
            [METHOD_BODY_BOUNDED]
            && nowMillis - timestamp >= Math.max(0L, processingTimeoutMillis);

            snippet:
            [SNIPPET_OMITTED_FROM_MODEL_VIEW: originalChars=270]
            """.strip();

    private static final String CONTROLLER = """
            Exact code chunk:
            repoId: bf4ef891-330b-4ce8-9002-ba4c43ffe210
            chunkId: 8edc54bf-73db-4be0-8ba6-f0145a714815
            file: src/main/java/com/flashdeal/controller/VoucherOrderController.java
            symbol: com.flashdeal.controller.VoucherOrderController#seckillVoucher
            type: CONTROLLER_API
            lines: 31-35

            content:
            @PostMapping("seckill/{id}")
                @RateLimit(key = "seckill:voucher", windowSeconds = 5, maxRequests = 3, dimension = RateLimitDimension.USER)
                public Result seckillVoucher(@PathVariable("id") Long voucherId) {
                    return voucherOrderService.seckillVoucher(voucherId);
                }
            """.strip();

    private static final String SECKILL_LUA = """
            Exact code chunk:
            repoId: bf4ef891-330b-4ce8-9002-ba4c43ffe210
            chunkId: 450de626-32e8-4eab-a81e-7012c48b33db
            file: src/main/resources/seckill.lua
            symbol: seckill.lua
            type: LUA_SCRIPT
            lines: 1-47

            content:
            -- ARGV[1]: voucher id
            -- ARGV[2]: user id
            -- ARGV[3]: order id
            -- ARGV[4]: message id
            -- ARGV[5]: current time millis
            local stockKey = 'seckill:stock:' .. voucherId
            local orderKey = 'seckill:order:' .. voucherId
            local pendingKey = 'seckill:pending'
            local pendingDetailKey = 'seckill:pending:detail:' .. orderId
            local reservationKey = 'seckill:reservation:' .. voucherId .. ':' .. userId
            local stock = redis.call('get', stockKey)
            if (not stock) then return 3 end
            -- 1: stock is not enough
            if (tonumber(stock) <= 0) then return 1 end
            -- 2: duplicate order by the same user
            if (redis.call('sismember', orderKey, userId) == 1) then return 2 end
            redis.call('incrby', stockKey, -1)
            redis.call('sadd', orderKey, userId)
            redis.call('set', reservationKey, orderId .. ':PENDING:' .. nowMillis)
            redis.call('zadd', pendingKey, nowMillis, orderId)
            redis.call('hset', pendingDetailKey, 'voucherId', voucherId, 'userId', userId,
                    'orderId', orderId, 'messageId', messageId, 'createTime', nowMillis)
            -- 0: seckill qualification passed and the pending reservation was recorded.
            return 0
            """.strip();

    private static final String TIMEOUT_DELAY_QUEUE = """
            Exact code chunk:
            repoId: bf4ef891-330b-4ce8-9002-ba4c43ffe210
            chunkId: 2aa9220e-3a91-4c11-bda9-fa3522af2841
            file: src/main/java/com/flashdeal/config/RabbitMqConfig.java
            symbol: com.flashdeal.config.RabbitMqConfig#orderTimeoutDelayQueue
            type: JAVA_METHOD
            lines: 165-172

            content:
            @Bean
                public Queue orderTimeoutDelayQueue() {
                    Map<String, Object> args = new HashMap<>();
                    args.put("x-message-ttl", orderTimeoutSeconds * 1000);
                    args.put("x-dead-letter-exchange", ORDER_CLOSE_EXCHANGE);
                    args.put("x-dead-letter-routing-key", ORDER_CLOSE_ROUTING_KEY);
                    return new Queue(ORDER_TIMEOUT_DELAY_QUEUE, true, false, false, args);
                }
            """.strip();

    private static final String TIMEOUT_BINDING = """
            Exact code chunk:
            repoId: bf4ef891-330b-4ce8-9002-ba4c43ffe210
            chunkId: 3cdb8c3a-26c6-4acd-af24-c36a26d1f0cd
            file: src/main/java/com/flashdeal/config/RabbitMqConfig.java
            symbol: com.flashdeal.config.RabbitMqConfig#orderTimeoutBinding
            type: JAVA_METHOD
            lines: 174-181

            content:
            @Bean
                public Binding orderTimeoutBinding(Queue orderTimeoutDelayQueue, DirectExchange orderTimeoutExchange) {
                    return BindingBuilder.bind(orderTimeoutDelayQueue)
                            .to(orderTimeoutExchange)
                            .with(ORDER_TIMEOUT_ROUTING_KEY);
                }
            """.strip();

    private static final String LISTENER_FACTORY = """
            Exact code chunk:
            repoId: bf4ef891-330b-4ce8-9002-ba4c43ffe210
            chunkId: 57467053-facd-4dea-8118-31abf434ebcd
            file: src/main/java/com/flashdeal/config/RabbitMqConfig.java
            symbol: com.flashdeal.config.RabbitMqConfig#rabbitListenerContainerFactory
            type: JAVA_METHOD
            lines: 255-270

            content:
            @Bean
                public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
                        ConnectionFactory connectionFactory, MessageConverter messageConverter,
                        @Qualifier("rabbitRetryInterceptor") RetryOperationsInterceptor rabbitRetryInterceptor) {
                    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
                    factory.setConnectionFactory(connectionFactory);
                    factory.setMessageConverter(messageConverter);
                    factory.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.MANUAL);
                    factory.setDefaultRequeueRejected(false);
                    factory.setAdviceChain(rabbitRetryInterceptor);
                    factory.setConcurrentConsumers(rabbitListenerConcurrency);
                    factory.setMaxConcurrentConsumers(rabbitListenerMaxConcurrency);
                    factory.setPrefetchCount(rabbitListenerPrefetch);
                    return factory;
                }
            """.strip();

    private static final String CREATE_ORDER = """
            Exact code chunk:
            repoId: bf4ef891-330b-4ce8-9002-ba4c43ffe210
            chunkId: 8a8a3f3b-578f-436b-92c1-ad9d7f0f0a89
            file: src/main/java/com/flashdeal/service/impl/VoucherOrderServiceImpl.java
            symbol: com.flashdeal.service.impl.VoucherOrderServiceImpl#createVoucherOrder
            type: SERVICE_METHOD
            lines: 272-314

            content:
            @Transactional
                void createVoucherOrder(VoucherOrder voucherOrder) {
                    Long userId = voucherOrder.getUserId();
                    Long voucherId = voucherOrder.getVoucherId();
                    RLock redisLock = redissonClient.getLock(SECKILL_ORDER_LOCK_KEY + voucherId + ":" + userId);
                    boolean isLock = redisLock.tryLock();
                    if (!isLock) throw new IllegalStateException("Seckill order is being processed");
                    try {
                        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
                        if (count > 0) return;
                        boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
                                .eq("voucher_id", voucherId).gt("stock", 0).update();
                        if (!success) throw new IllegalStateException("stock is not enough");
                        voucherOrder.setStatus(VoucherOrderStatus.UNPAID.getCode());
                        voucherOrder.setCreateTime(LocalDateTime.now());
                        if (!save(voucherOrder)) throw new IllegalStateException("Save failed");
                        outboxEventService.saveOrderTimeoutEvents(Collections.singletonList(voucherOrder));
                    } catch (DuplicateKeyException e) {
                        throw e;
                    } finally {
                        redisLock.unlock();
                    }
                }
            """.strip();

    private static final String RECOVERY_SQL = """
            Exact code chunk:
            repoId: bf4ef891-330b-4ce8-9002-ba4c43ffe210
            chunkId: ac4dfed8-1621-4d54-88ba-e0844b74c640
            file: src/main/resources/mapper/OrderTimeoutCloseFailMapper.xml
            symbol: com.flashdeal.mapper.OrderTimeoutCloseFailMapper.recoverStuckProcessing
            type: MYBATIS_SQL
            lines: 69-78

            content:
            <update id="recoverStuckProcessing">
                UPDATE tb_order_timeout_close_fail
                SET status = 'INIT', next_retry_time = #{nextRetryTime},
                    last_fail_reason = 'ORDER_CLOSE_RETRY_PROCESSING_TIMEOUT'
                WHERE status = 'PROCESSING' AND update_time &lt;= #{staleBefore}
                ORDER BY update_time ASC LIMIT #{limit}
            </update>
            """.strip();

    private static final String PROCESSING_TIMEOUT = """
            Exact code chunk:
            repoId: bf4ef891-330b-4ce8-9002-ba4c43ffe210
            chunkId: 609a18b5-9b39-4e8a-8350-c1d88fd58d3a
            file: src/main/java/com/flashdeal/dto/SeckillReservationState.java
            symbol: com.flashdeal.dto.SeckillReservationState#isProcessingTimedOut
            type: JAVA_METHOD
            lines: 16-20

            content:
            public boolean isProcessingTimedOut(long nowMillis, long processingTimeoutMillis) {
                return status == SeckillReservationStatus.PROCESSING
                        && timestamp != null
                        && nowMillis - timestamp >= Math.max(0L, processingTimeoutMillis);
            }
            """.strip();

    private static final String ROLLBACK_LUA = """
            Exact code chunk:
            repoId: bf4ef891-330b-4ce8-9002-ba4c43ffe210
            chunkId: 5803c9cf-129a-43bd-b3ca-fba7afe9fcf0
            file: src/main/resources/seckill_rollback.lua
            symbol: seckill_rollback.lua
            type: LUA_SCRIPT
            lines: 1-56

            content:
            -- ARGV[1]: voucher id; ARGV[2]: user id; ARGV[3]: order id
            -- ARGV[4]: current time millis; ARGV[5]: processing timeout millis
            local reservation = redis.call('get', reservationKey)
            if (not reservation) then
                redis.call('zrem', pendingKey, orderId)
                redis.call('del', pendingDetailKey)
                return 1
            end
            local pendingValue = orderId .. ':PENDING:'
            local processingValue = orderId .. ':PROCESSING:'
            local rollbackAllowed = string.sub(reservation, 1, string.len(pendingValue)) == pendingValue
            if (not rollbackAllowed and string.sub(reservation, 1, string.len(processingValue)) == processingValue) then
                local processingTime = tonumber(string.sub(reservation, string.len(processingValue) + 1))
                rollbackAllowed = processingTime and nowMillis - processingTime >= processingTimeoutMillis
            end
            if (not rollbackAllowed) then return 3 end
            if (not redis.call('get', stockKey)) then return 2 end
            redis.call('srem', orderKey, userId)
            redis.call('incrby', stockKey, 1)
            redis.call('del', reservationKey)
            redis.call('zrem', pendingKey, orderId)
            redis.call('del', pendingDetailKey)
            return 0
            """.strip();

    private static final String RABBIT_CONSTANTS = """
            Exact code chunk:
            repoId: bf4ef891-330b-4ce8-9002-ba4c43ffe210
            chunkId: 13b800f8-8acf-49f6-a840-44b058154de3
            file: src/main/java/com/flashdeal/utils/RabbitConstants.java
            symbol: com.flashdeal.utils.RabbitConstants#classMembers
            type: JAVA_CLASS_MEMBER
            lines: 5-28

            content:
            public static final String SECKILL_ORDER_EXCHANGE = "flashdeal.seckill.order.exchange";
            public static final String SECKILL_ORDER_QUEUE = "flashdeal.seckill.order.queue";
            public static final String SECKILL_ORDER_ROUTING_KEY = "flashdeal.seckill.order";
            public static final String ORDER_TIMEOUT_DELAY_QUEUE = "flashdeal.order.timeout.delay.queue";
            public static final String ORDER_CLOSE_EXCHANGE = "flashdeal.order.close.exchange";
            public static final String ORDER_CLOSE_QUEUE = "flashdeal.order.close.queue";
            public static final String ORDER_CLOSE_ROUTING_KEY = "flashdeal.order.close";
            """.strip();

    private static final String PRODUCER = """
            Exact code chunk:
            repoId: bf4ef891-330b-4ce8-9002-ba4c43ffe210
            chunkId: 2b2ba849-d84c-453b-b23a-2aa8c0dd7d90
            file: src/main/java/com/flashdeal/mq/VoucherOrderProducer.java
            symbol: com.flashdeal.mq.VoucherOrderProducer#sendSeckillOrder
            type: JAVA_METHOD
            lines: 64-81

            content:
            public void sendSeckillOrder(VoucherOrderMessage message) {
                try {
                    doSendSeckillOrder(message);
                } catch (RuntimeException e) {
                    mqMessageService.markSendFailed(message.getMessageId(), limitReason(e.getMessage()),
                            LocalDateTime.now().plusSeconds(sendFailedNextRetryDelaySeconds));
                    throw e;
                }
                mqMessageService.markSent(message.getMessageId());
            }
            """.strip();

    private static final String CONSUMER = """
            Exact code chunk:
            repoId: bf4ef891-330b-4ce8-9002-ba4c43ffe210
            chunkId: e73a197a-ec27-4de3-aa82-a8a7a06864ef
            file: src/main/java/com/flashdeal/mq/VoucherOrderConsumer.java
            symbol: com.flashdeal.mq.VoucherOrderConsumer#handleSeckillOrderBatch
            type: JAVA_METHOD
            lines: 75-94

            content:
            @RabbitListener(queues = SECKILL_ORDER_QUEUE,
                    containerFactory = "seckillOrderBatchRabbitListenerContainerFactory")
            public void handleSeckillOrderBatch(List<Message> messages, Channel channel) throws Exception {
                if (messages == null || messages.isEmpty()) return;
                List<BatchMessageItem> items = new ArrayList<>(messages.size());
                for (Message message : messages) {
                    try {
                        VoucherOrderMessage orderMessage = objectMapper.readValue(message.getBody(), VoucherOrderMessage.class);
                        items.add(new BatchMessageItem(message, orderMessage));
                    } catch (Exception e) {
                        channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, false);
                    }
                }
                processWithRetry(prepareClaimedItems(items, channel), channel);
            }
            """.strip();
}
