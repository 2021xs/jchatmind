package com.kama.jchatmind.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.AsyncConfig;
import com.kama.jchatmind.config.AsyncExecutorProperties;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.config.MultiChatClientConfig;
import com.kama.jchatmind.model.dto.CodeEvidenceCandidateCard;
import com.kama.jchatmind.model.dto.CodeEvidenceSelectionResult;
import com.kama.jchatmind.service.impl.CodeLlmEvidenceSelector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real LLM evaluation for selector thread-pool sizing.
 *
 * <p>This test is deliberately gated because it performs paid network calls. It fixes the selector
 * input so embedding and pgvector latency are not included in the measurements.</p>
 */
@EnabledIfEnvironmentVariable(named = "JCHATMIND_REAL_SELECTOR_EVAL_ENABLED", matches = "true")
class CodeLlmSelectorThreadPoolEvaluationTest {

    private static final int SELECTOR_TIMEOUT_MS = 30_000;
    private static final int MAX_ACCEPTABLE_P95_MS = SELECTOR_TIMEOUT_MS / 2;
    private static final int PEAK_CONCURRENCY = 8;
    private static final int PEAK_ROUNDS = 3;
    private static final int OVERLOAD_CONCURRENCY = 12;
    private static final int OVERLOAD_ROUNDS = 1;
    private static final String EXPECTED_CONTROLLER_ID = "candidate-controller";
    private static final String EXPECTED_SERVICE_ID = "candidate-service";
    private static final String EXPECTED_SQL_ID = "candidate-sql";
    private static final Path CSV_PATH = Path.of("docs/eval/llm_selector_thread_pool_results.csv");
    private static final Path REPORT_PATH = Path.of("docs/eval/llm_selector_thread_pool_report.md");

    private static final List<PoolProfile> PEAK_PROFILES = List.of(
            new PoolProfile("2/2/6", 2, 2, 6),
            new PoolProfile("4/4/0", 4, 4, 0),
            new PoolProfile("4/4/4", 4, 4, 4),
            new PoolProfile("4/4/6", 4, 4, 6),
            new PoolProfile("4/4/8", 4, 4, 8),
            new PoolProfile("8/8/0", 8, 8, 0)
    );

    private static final List<PoolProfile> OVERLOAD_PROFILES = List.of(
            new PoolProfile("4/4/4", 4, 4, 4),
            new PoolProfile("4/4/6", 4, 4, 6),
            new PoolProfile("4/4/8", 4, 4, 8)
    );

    private static final List<QueryCase> QUERY_CASES = List.of(
            new QueryCase("Which endpoint handles creating an order?", EXPECTED_CONTROLLER_ID),
            new QueryCase("Where is the order creation business logic implemented?", EXPECTED_SERVICE_ID),
            new QueryCase("Which MyBatis statement inserts an order?", EXPECTED_SQL_ID)
    );

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void evaluatesRealSelectorThreadPoolProfiles() throws Exception {
        ModelConfig modelConfig = loadModelConfig();
        ChatClient chatClient = new MultiChatClientConfig().gptCompatibleChatClient(
                modelConfig.apiKey(), modelConfig.baseUrl(), modelConfig.model());
        ChatClientRegistry registry = new ChatClientRegistry(Map.of(modelConfig.model(), chatClient));

        warmUp(registry, modelConfig.model());

        List<RequestRecord> records = new ArrayList<>();
        List<ScenarioRecord> scenarios = new ArrayList<>();
        for (PoolProfile profile : PEAK_PROFILES) {
            evaluateProfile(registry, modelConfig.model(), profile, "PEAK_8",
                    PEAK_CONCURRENCY, PEAK_ROUNDS, records, scenarios);
        }
        for (PoolProfile profile : OVERLOAD_PROFILES) {
            evaluateProfile(registry, modelConfig.model(), profile, "OVERLOAD_12",
                    OVERLOAD_CONCURRENCY, OVERLOAD_ROUNDS, records, scenarios);
        }

        List<Aggregate> aggregates = aggregate(records, scenarios);
        String recommendation = recommend(aggregates);
        writeCsv(records);
        writeReport(modelConfig.model(), records, aggregates, recommendation);

        assertTrue(records.stream().anyMatch(record -> !record.fallback()),
                "Real selector evaluation produced no successful LLM selection; inspect the generated report");
    }

    private void warmUp(ChatClientRegistry registry, String model) {
        PoolProfile profile = new PoolProfile("warmup", 1, 1, 0);
        ThreadPoolTaskExecutor executor = createExecutor(profile);
        try {
            CodeLlmEvidenceSelector selector = createSelector(registry, model, executor);
            for (int i = 0; i < 2; i++) {
                selector.select(QUERY_CASES.get(i).query(), candidates());
            }
        } finally {
            executor.shutdown();
        }
    }

    private void evaluateProfile(ChatClientRegistry registry,
                                 String model,
                                 PoolProfile profile,
                                 String scenario,
                                 int concurrency,
                                 int rounds,
                                 List<RequestRecord> records,
                                 List<ScenarioRecord> scenarios) throws Exception {
        ThreadPoolTaskExecutor selectorExecutor = createExecutor(profile);
        CodeLlmEvidenceSelector selector = createSelector(registry, model, selectorExecutor);
        try {
            for (int round = 1; round <= rounds; round++) {
                ScenarioExecution execution = runBurst(selector, selectorExecutor, profile,
                        scenario, concurrency, round);
                records.addAll(execution.records());
                scenarios.add(execution.scenario());
            }
        } finally {
            selectorExecutor.shutdown();
        }
    }

    private ScenarioExecution runBurst(CodeLlmEvidenceSelector selector,
                                       ThreadPoolTaskExecutor selectorExecutor,
                                       PoolProfile profile,
                                       String scenario,
                                       int concurrency,
                                       int round) throws Exception {
        ExecutorService loadGenerator = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger maxActive = new AtomicInteger();
        AtomicInteger maxQueue = new AtomicInteger();
        AtomicBoolean sampling = new AtomicBoolean(true);
        Thread sampler = new Thread(() -> samplePool(selectorExecutor, sampling, maxActive, maxQueue),
                "selector-eval-sampler");
        sampler.setDaemon(true);
        sampler.start();

        List<Future<RequestRecord>> futures = new ArrayList<>();
        long burstStarted = System.nanoTime();
        try {
            for (int requestIndex = 0; requestIndex < concurrency; requestIndex++) {
                int index = requestIndex;
                futures.add(loadGenerator.submit(() -> {
                    ready.countDown();
                    start.await();
                    QueryCase queryCase = QUERY_CASES.get(index % QUERY_CASES.size());
                    CodeEvidenceSelectionResult result = selector.select(queryCase.query(), candidates());
                    return toRecord(profile, scenario, round, index, concurrency, queryCase, result);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "Load generator did not become ready in time");
            burstStarted = System.nanoTime();
            start.countDown();

            List<RequestRecord> burstRecords = new ArrayList<>();
            for (Future<RequestRecord> future : futures) {
                burstRecords.add(future.get(SELECTOR_TIMEOUT_MS + 60_000L, TimeUnit.MILLISECONDS));
            }
            long durationMs = elapsedMs(burstStarted);
            return new ScenarioExecution(
                    burstRecords,
                    new ScenarioRecord(profile.name(), scenario, round, concurrency, durationMs,
                            maxActive.get(), maxQueue.get())
            );
        } finally {
            start.countDown();
            sampling.set(false);
            sampler.join(1_000);
            loadGenerator.shutdownNow();
        }
    }

    private void samplePool(ThreadPoolTaskExecutor executor,
                            AtomicBoolean sampling,
                            AtomicInteger maxActive,
                            AtomicInteger maxQueue) {
        while (sampling.get()) {
            maxActive.accumulateAndGet(executor.getActiveCount(), Math::max);
            maxQueue.accumulateAndGet(executor.getThreadPoolExecutor().getQueue().size(), Math::max);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private RequestRecord toRecord(PoolProfile profile,
                                   String scenario,
                                   int round,
                                   int requestIndex,
                                   int concurrency,
                                   QueryCase queryCase,
                                   CodeEvidenceSelectionResult result) {
        String reason = sanitize(result.getReason());
        String reasonLower = reason.toLowerCase(Locale.ROOT);
        boolean rejected = result.isFallback()
                && (reasonLower.contains("reject") || reasonLower.contains("did not accept"));
        boolean rateLimited = reasonLower.contains("429") || reasonLower.contains("rate limit")
                || reasonLower.contains("too many requests");
        boolean timedOut = result.isFallback()
                && (reasonLower.contains("timeout") || result.getLatencyMs() >= SELECTOR_TIMEOUT_MS - 1_000L);
        boolean expectedHit = result.getSelectedChunkIds() != null
                && result.getSelectedChunkIds().contains(queryCase.expectedChunkId());
        return new RequestRecord(
                OffsetDateTime.now().toString(), profile.name(), scenario, round, requestIndex,
                profile.core(), profile.max(), profile.queue(), concurrency, SELECTOR_TIMEOUT_MS,
                result.getLatencyMs(), result.isFallback(), result.isJsonParseOk(), expectedHit,
                rejected, timedOut, rateLimited, reason
        );
    }

    private ThreadPoolTaskExecutor createExecutor(PoolProfile profile) {
        AsyncExecutorProperties properties = new AsyncExecutorProperties();
        AsyncExecutorProperties.Pool pool = properties.getCodeEvidenceSelector();
        pool.setCorePoolSize(profile.core());
        pool.setMaxPoolSize(profile.max());
        pool.setQueueCapacity(profile.queue());
        pool.setAwaitTerminationSeconds(60);
        return new AsyncConfig(properties).codeEvidenceSelectorExecutor();
    }

    private CodeLlmEvidenceSelector createSelector(ChatClientRegistry registry,
                                                   String model,
                                                   ThreadPoolTaskExecutor executor) {
        CodeRagProperties properties = new CodeRagProperties();
        properties.getLlmSelector().setEnabled(true);
        properties.getLlmSelector().setModel(model);
        properties.getLlmSelector().setMaxCandidateChars(600);
        properties.getLlmSelector().setMaxSelected(3);
        properties.getLlmSelector().setTimeoutMs(SELECTOR_TIMEOUT_MS);
        return new CodeLlmEvidenceSelector(registry, properties, new ObjectMapper(), executor);
    }

    private List<CodeEvidenceCandidateCard> candidates() {
        return List.of(
                CodeEvidenceCandidateCard.builder()
                        .chunkId(EXPECTED_CONTROLLER_ID)
                        .chunkType("CONTROLLER_API")
                        .filePath("src/main/java/example/OrderController.java")
                        .symbolName("createOrder")
                        .apiPath("/api/orders")
                        .httpMethod("POST")
                        .metadataSummary("METHOD=POST API_PATH=/api/orders")
                        .evidenceRole("API entry")
                        .evidenceHint("Handles the HTTP request and delegates to OrderService")
                        .snippet("@PostMapping(\"/api/orders\") public Order createOrder(...) { return orderService.createOrder(...); }")
                        .source("RAW_VECTOR")
                        .rawRank(1)
                        .candidateRank(1)
                        .candidateScore(0.92)
                        .build(),
                CodeEvidenceCandidateCard.builder()
                        .chunkId(EXPECTED_SERVICE_ID)
                        .chunkType("SERVICE_METHOD")
                        .filePath("src/main/java/example/OrderService.java")
                        .symbolName("createOrder")
                        .metadataSummary("business service transaction")
                        .evidenceRole("Business logic")
                        .evidenceHint("Validates the order and calls OrderMapper.insertOrder")
                        .snippet("public Order createOrder(...) { validate(...); orderMapper.insertOrder(...); return order; }")
                        .source("RAW_VECTOR")
                        .rawRank(2)
                        .candidateRank(2)
                        .candidateScore(0.88)
                        .build(),
                CodeEvidenceCandidateCard.builder()
                        .chunkId(EXPECTED_SQL_ID)
                        .chunkType("MYBATIS_SQL")
                        .filePath("src/main/resources/mapper/OrderMapper.xml")
                        .symbolName("insertOrder")
                        .metadataSummary("SQL_ID=insertOrder statementType=INSERT table=orders")
                        .evidenceRole("Persistence SQL")
                        .evidenceHint("Inserts an order row into the orders table")
                        .snippet("<insert id=\"insertOrder\">INSERT INTO orders (...) VALUES (...)</insert>")
                        .source("RAW_VECTOR")
                        .rawRank(3)
                        .candidateRank(3)
                        .candidateScore(0.84)
                        .build()
        );
    }

    private List<Aggregate> aggregate(List<RequestRecord> records, List<ScenarioRecord> scenarios) {
        List<Aggregate> aggregates = new ArrayList<>();
        List<String> keys = records.stream()
                .map(record -> record.profile() + "\u0000" + record.scenario())
                .distinct()
                .toList();
        for (String key : keys) {
            String[] parts = key.split("\u0000", -1);
            String profile = parts[0];
            String scenario = parts[1];
            List<RequestRecord> group = records.stream()
                    .filter(record -> record.profile().equals(profile) && record.scenario().equals(scenario))
                    .toList();
            List<Long> latencies = group.stream().map(RequestRecord::latencyMs).sorted().toList();
            List<ScenarioRecord> scenarioGroup = scenarios.stream()
                    .filter(record -> record.profile().equals(profile) && record.scenario().equals(scenario))
                    .toList();
            aggregates.add(new Aggregate(
                    profile,
                    scenario,
                    group.size(),
                    count(group, RequestRecord::fallback),
                    count(group, RequestRecord::rejected),
                    count(group, RequestRecord::timedOut),
                    count(group, RequestRecord::rateLimited),
                    count(group, RequestRecord::jsonParseOk),
                    count(group, RequestRecord::expectedHit),
                    Math.round(group.stream().mapToLong(RequestRecord::latencyMs).average().orElse(0)),
                    percentile(latencies, 0.50),
                    percentile(latencies, 0.95),
                    latencies.get(0),
                    latencies.get(latencies.size() - 1),
                    Math.round(scenarioGroup.stream().mapToLong(ScenarioRecord::durationMs).average().orElse(0)),
                    scenarioGroup.stream().mapToInt(ScenarioRecord::maxActive).max().orElse(0),
                    scenarioGroup.stream().mapToInt(ScenarioRecord::maxQueue).max().orElse(0)
            ));
        }
        aggregates.sort(Comparator.comparing(Aggregate::scenario).thenComparing(Aggregate::profile));
        return aggregates;
    }

    private String recommend(List<Aggregate> aggregates) {
        return aggregates.stream()
                .filter(aggregate -> aggregate.scenario().equals("PEAK_8"))
                .filter(aggregate -> aggregate.fallbackCount() == 0)
                .filter(aggregate -> aggregate.timeoutCount() == 0)
                .filter(aggregate -> aggregate.rateLimitCount() == 0)
                .filter(aggregate -> rate(aggregate.jsonParseOkCount(), aggregate.requests()) == 1.0)
                .filter(aggregate -> parseProfile(aggregate.profile()).max() >= PEAK_CONCURRENCY / 2)
                .filter(aggregate -> aggregate.p95LatencyMs() <= MAX_ACCEPTABLE_P95_MS)
                .map(aggregate -> new RecommendedProfile(aggregate.profile(), parseProfile(aggregate.profile())))
                .min(Comparator.comparingInt((RecommendedProfile item) -> item.profile().max())
                        .thenComparingInt(item -> item.profile().queue()))
                .map(RecommendedProfile::name)
                .orElse("NONE");
    }

    private PoolProfile parseProfile(String value) {
        String[] parts = value.split("/");
        return new PoolProfile(value, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]));
    }

    private void writeCsv(List<RequestRecord> records) throws IOException {
        Files.createDirectories(CSV_PATH.getParent());
        StringBuilder csv = new StringBuilder();
        csv.append("timestamp,profile,scenario,round,request_index,core_pool_size,max_pool_size,queue_capacity,")
                .append("concurrency,timeout_ms,latency_ms,fallback,json_parse_ok,expected_hit,rejected,timeout,")
                .append("rate_limited,reason\n");
        for (RequestRecord record : records) {
            csv.append(csv(record.timestamp())).append(',')
                    .append(record.profile()).append(',')
                    .append(record.scenario()).append(',')
                    .append(record.round()).append(',')
                    .append(record.requestIndex()).append(',')
                    .append(record.core()).append(',')
                    .append(record.max()).append(',')
                    .append(record.queue()).append(',')
                    .append(record.concurrency()).append(',')
                    .append(record.timeoutMs()).append(',')
                    .append(record.latencyMs()).append(',')
                    .append(record.fallback()).append(',')
                    .append(record.jsonParseOk()).append(',')
                    .append(record.expectedHit()).append(',')
                    .append(record.rejected()).append(',')
                    .append(record.timedOut()).append(',')
                    .append(record.rateLimited()).append(',')
                    .append(csv(record.reason())).append('\n');
        }
        Files.writeString(CSV_PATH, csv.toString(), StandardCharsets.UTF_8);
    }

    private void writeReport(String model,
                             List<RequestRecord> records,
                             List<Aggregate> aggregates,
                             String recommendation) throws IOException {
        Files.createDirectories(REPORT_PATH.getParent());
        StringBuilder report = new StringBuilder();
        report.append("# LLM Selector 线程池真实评测报告\n\n")
                .append("## 测试说明\n\n")
                .append("- 运行时间：").append(OffsetDateTime.now()).append("\n")
                .append("- 模型：").append(model).append("\n")
                .append("- Java：").append(System.getProperty("java.version")).append("\n")
                .append("- 可用处理器：").append(Runtime.getRuntime().availableProcessors()).append("\n")
                .append("- selector timeout：").append(SELECTOR_TIMEOUT_MS).append(" ms\n")
                .append("- 正常峰值：8 并发 × ").append(PEAK_ROUNDS).append(" 轮\n")
                .append("- 过载场景：12 并发 × ").append(OVERLOAD_ROUNDS).append(" 轮\n")
                .append("- 输入：固定 3 个候选卡片和 3 类 query，直接调用真实 `CodeLlmEvidenceSelector.select`。\n")
                .append("- 隔离范围：不包含 embedding、pgvector、Agent 主循环或最终回答耗时。\n")
                .append("- 证据命中仅用于固定输入冒烟检查，不替代 80 条 Code RAG final eval。\n")
                .append("- 原始记录：`docs/eval/llm_selector_thread_pool_results.csv`。\n\n")
                .append("## 运行命令\n\n")
                .append("```powershell\n")
                .append("$env:JAVA_HOME='C:\\Program Files\\Java\\jdk-21'\n")
                .append("$env:PATH=\"$env:JAVA_HOME\\bin;$env:PATH\"\n")
                .append("$env:JCHATMIND_REAL_SELECTOR_EVAL_ENABLED='true'\n")
                .append(".\\mvnw.cmd \"-Dtest=CodeLlmSelectorThreadPoolEvaluationTest\" test\n")
                .append("```\n\n")
                .append("## 聚合结果\n\n")
                .append("| 参数(core/max/queue) | 场景 | 请求数 | fallback | reject | timeout | 429 | JSON成功率 | 证据命中率 | 平均ms | P50ms | P95ms | min/max ms | 平均批次ms | max active | max queue |\n")
                .append("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (Aggregate aggregate : aggregates) {
            report.append("| ").append(aggregate.profile())
                    .append(" | ").append(aggregate.scenario())
                    .append(" | ").append(aggregate.requests())
                    .append(" | ").append(aggregate.fallbackCount())
                    .append(" | ").append(aggregate.rejectedCount())
                    .append(" | ").append(aggregate.timeoutCount())
                    .append(" | ").append(aggregate.rateLimitCount())
                    .append(" | ").append(percent(aggregate.jsonParseOkCount(), aggregate.requests()))
                    .append(" | ").append(percent(aggregate.expectedHitCount(), aggregate.requests()))
                    .append(" | ").append(aggregate.averageLatencyMs())
                    .append(" | ").append(aggregate.p50LatencyMs())
                    .append(" | ").append(aggregate.p95LatencyMs())
                    .append(" | ").append(aggregate.minLatencyMs()).append('/').append(aggregate.maxLatencyMs())
                    .append(" | ").append(aggregate.averageBurstMs())
                    .append(" | ").append(aggregate.maxActive())
                    .append(" | ").append(aggregate.maxQueue())
                    .append(" |\n");
        }

        Aggregate recommendedPeak = findAggregate(aggregates, recommendation, "PEAK_8");
        Aggregate recommendedOverload = findAggregate(aggregates, recommendation, "OVERLOAD_12");
        Aggregate tightPeak = findAggregate(aggregates, "4/4/4", "PEAK_8");
        Aggregate largeQueueOverload = findAggregate(aggregates, "4/4/8", "OVERLOAD_12");
        Aggregate largePoolPeak = findAggregate(aggregates, "8/8/0", "PEAK_8");
        report.append("\n## 参数选择解释\n\n");
        if (recommendedPeak != null) {
            report.append("- 推荐参数 `").append(recommendation).append("` 在正常峰值下 ")
                    .append(recommendedPeak.requests()).append(" 个请求中 fallback=")
                    .append(recommendedPeak.fallbackCount()).append("、timeout=")
                    .append(recommendedPeak.timeoutCount()).append("、429=")
                    .append(recommendedPeak.rateLimitCount()).append("，P95=")
                    .append(recommendedPeak.p95LatencyMs()).append("ms。\n");
        }
        if (tightPeak != null) {
            report.append("- `4/4/4` 在连续峰值批次中 reject=")
                    .append(tightPeak.rejectedCount())
                    .append("，说明运行容量与名义峰值完全相等时缺少任务交接余量。\n");
        }
        if (recommendedOverload != null && largeQueueOverload != null) {
            report.append("- 12 并发过载时，`").append(recommendation).append("` reject=")
                    .append(recommendedOverload.rejectedCount()).append("、P95=")
                    .append(recommendedOverload.p95LatencyMs()).append("ms；`4/4/8` reject=")
                    .append(largeQueueOverload.rejectedCount()).append("、P95=")
                    .append(largeQueueOverload.p95LatencyMs())
                    .append("ms。较小队列通过现有 fallback 提供明确的上游保护。\n");
        }
        if (largePoolPeak != null && recommendedPeak != null) {
            report.append("- `8/8/0` P95=").append(largePoolPeak.p95LatencyMs())
                    .append("ms，但最大 LLM 并发为 ").append(largePoolPeak.maxActive())
                    .append("；推荐参数最大 LLM 并发为 ").append(recommendedPeak.maxActive())
                    .append(" 且已经满足验收线，因此不扩大外部并发。\n");
        }

        report.append("\n## 自动验收规则\n\n")
                .append("正常峰值场景必须同时满足：fallback=0、timeout=0、429=0、JSON解析成功率=100%、")
                .append("最大线程数≥峰值并发的一半（最多两波执行）、P95≤timeout/2=")
                .append(MAX_ACCEPTABLE_P95_MS).append("ms。")
                .append("满足规则后优先选择更小的最大线程数，再选择更小的队列。\n\n")
                .append("## 推荐结果\n\n")
                .append("- 推荐参数：`").append(recommendation).append("`。\n")
                .append("- 评测前默认参数：`4/4/8`。\n")
                .append("- 本次落地默认参数：`4/4/6`。\n")
                .append("- 本报告是本机真实 API 小样本基准，不声称跨机器、跨模型或跨供应商绝对最优；部署后仍需持续观察 P95、fallback、timeout 和 429。\n\n")
                .append("## 原始执行统计\n\n")
                .append("- 总请求记录：").append(records.size()).append("\n")
                .append("- 非 fallback：").append(count(records, record -> !record.fallback())).append("\n")
                .append("- fallback：").append(count(records, RequestRecord::fallback)).append("\n")
                .append("- timeout：").append(count(records, RequestRecord::timedOut)).append("\n")
                .append("- 429：").append(count(records, RequestRecord::rateLimited)).append("\n");
        Files.writeString(REPORT_PATH, report.toString(), StandardCharsets.UTF_8);
    }

    private Aggregate findAggregate(List<Aggregate> aggregates, String profile, String scenario) {
        return aggregates.stream()
                .filter(aggregate -> aggregate.profile().equals(profile)
                        && aggregate.scenario().equals(scenario))
                .findFirst()
                .orElse(null);
    }

    private ModelConfig loadModelConfig() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        addYaml(environment, "application-local", new FileSystemResource("application-local.yaml"));
        addYaml(environment, "application", new FileSystemResource("src/main/resources/application.yaml"));
        String apiKey = requiredResolved(environment, "jchatmind.ai.gpt.compatible.api-key");
        String baseUrl = requiredResolved(environment, "jchatmind.ai.gpt.compatible.base-url");
        String model = requiredResolved(environment, "jchatmind.ai.gpt.compatible.model");
        if ("your-api-key".equals(apiKey)) {
            throw new IllegalStateException("No usable GPT-compatible API credential was resolved");
        }
        return new ModelConfig(apiKey, baseUrl, model);
    }

    private void addYaml(StandardEnvironment environment, String name, Resource resource) throws IOException {
        if (!resource.exists()) {
            return;
        }
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load(name, resource);
        for (PropertySource<?> source : sources) {
            environment.getPropertySources().addLast(source);
        }
    }

    private String requiredResolved(StandardEnvironment environment, String key) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required model configuration is missing: " + key);
        }
        return environment.resolveRequiredPlaceholders(value);
    }

    private long percentile(List<Long> sortedValues, double percentile) {
        int index = Math.max(0, (int) Math.ceil(percentile * sortedValues.size()) - 1);
        return sortedValues.get(index);
    }

    private long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000L);
    }

    private <T> long count(List<T> values, BooleanValue<T> predicate) {
        return values.stream().filter(predicate::test).count();
    }

    private double rate(long value, long total) {
        return total == 0 ? 0 : (double) value / total;
    }

    private String percent(long value, long total) {
        return String.format(Locale.ROOT, "%.1f%%", rate(value, total) * 100.0);
    }

    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String sanitized = value.replaceAll("(?i)Bearer\\s+\\S+", "Bearer <redacted>")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
        return sanitized.length() <= 500 ? sanitized : sanitized.substring(0, 500);
    }

    private String csv(String value) {
        String safe = value == null ? "" : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    @FunctionalInterface
    private interface BooleanValue<T> {
        boolean test(T value);
    }

    private record ModelConfig(String apiKey, String baseUrl, String model) {
    }

    private record PoolProfile(String name, int core, int max, int queue) {
    }

    private record RecommendedProfile(String name, PoolProfile profile) {
    }

    private record QueryCase(String query, String expectedChunkId) {
    }

    private record RequestRecord(String timestamp,
                                 String profile,
                                 String scenario,
                                 int round,
                                 int requestIndex,
                                 int core,
                                 int max,
                                 int queue,
                                 int concurrency,
                                 int timeoutMs,
                                 long latencyMs,
                                 boolean fallback,
                                 boolean jsonParseOk,
                                 boolean expectedHit,
                                 boolean rejected,
                                 boolean timedOut,
                                 boolean rateLimited,
                                 String reason) {
    }

    private record ScenarioRecord(String profile,
                                  String scenario,
                                  int round,
                                  int concurrency,
                                  long durationMs,
                                  int maxActive,
                                  int maxQueue) {
    }

    private record ScenarioExecution(List<RequestRecord> records, ScenarioRecord scenario) {
    }

    private record Aggregate(String profile,
                             String scenario,
                             int requests,
                             long fallbackCount,
                             long rejectedCount,
                             long timeoutCount,
                             long rateLimitCount,
                             long jsonParseOkCount,
                             long expectedHitCount,
                             long averageLatencyMs,
                             long p50LatencyMs,
                             long p95LatencyMs,
                             long minLatencyMs,
                             long maxLatencyMs,
                             long averageBurstMs,
                             int maxActive,
                             int maxQueue) {
    }
}
