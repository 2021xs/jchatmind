package com.kama.jchatmind.benchmark.compression;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.kama.jchatmind.agent.observability.AgentLifecycleObservationPublisher;
import com.kama.jchatmind.config.ContextCompressionProperties;
import com.kama.jchatmind.config.MultiChatClientConfig;
import com.kama.jchatmind.mapper.AgentTaskMapper;
import com.kama.jchatmind.mapper.ChatSessionMapper;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.service.ConversationContextCompressor;
import com.kama.jchatmind.service.ConversationSummaryClient;
import com.kama.jchatmind.service.impl.ConversationContextCompressorImpl;
import com.kama.jchatmind.service.impl.EstimatedTokenCounter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@Tag("compression-replay-benchmark")
@EnabledIf("enabled")
class CompressionModelReplayBenchmarkTest {
    private static final String MEASUREMENT_MODEL = "compression-replay-fixed-budget";
    private static final String DEEPSEEK_MODEL = "deepseek-v4-flash";
    private static final int CHARS_PER_TOKEN = 3;
    private static final int PILOT_REPEATS = 2;
    private static final double STOCHASTIC_TOLERANCE = 0.05;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    void compareFrozenSparseDeltaCompressionAcrossModels() throws Exception {
        OffsetDateTime startedAt = OffsetDateTime.now();
        ReplayConfiguration configuration = loadConfiguration();
        List<CompressionReplayScenarios.Scenario> scenarios = CompressionReplayScenarios.all();
        require(scenarios.size() >= 8, "Compression replay corpus must contain at least 8 scenarios");
        validateScenarioContracts(scenarios);
        verifyCompressionPressure(scenarios);
        verifyWireContracts(configuration);

        String replaySource = System.getProperty("compression.replay.source");
        if (StringUtils.hasText(replaySource)) {
            Path output = reevaluateArtifact(Path.of(replaySource), scenarios, configuration);
            System.out.println("Compression replay re-evaluated output: " + output.toAbsolutePath());
            return;
        }

        ChatClient gptChatClient = new MultiChatClientConfig().gptCompatibleChatClient(
                configuration.gptApiKey(), configuration.gptBaseUrl(), configuration.gptModel());
        Map<CompressionReplayClients.Variant, ClientFactory> factories = new EnumMap<>(CompressionReplayClients.Variant.class);
        factories.put(CompressionReplayClients.Variant.GPT_BASELINE,
                () -> CompressionReplayClients.gpt(gptChatClient, configuration.gptModel(), CHARS_PER_TOKEN));
        factories.put(CompressionReplayClients.Variant.DS_THINKING,
                () -> CompressionReplayClients.deepSeek(RestClient.builder(), objectMapper,
                        configuration.deepSeekBaseUrl(), configuration.deepSeekApiKey(), DEEPSEEK_MODEL,
                        CompressionReplayClients.ThinkingMode.ENABLED, null, CHARS_PER_TOKEN));
        factories.put(CompressionReplayClients.Variant.DS_NON_THINKING,
                () -> CompressionReplayClients.deepSeek(RestClient.builder(), objectMapper,
                        configuration.deepSeekBaseUrl(), configuration.deepSeekApiKey(), DEEPSEEK_MODEL,
                        CompressionReplayClients.ThinkingMode.DISABLED, null, CHARS_PER_TOKEN));

        List<Observation> observations = new ArrayList<>();
        executeRepeats(observations, scenarios, factories, 1, PILOT_REPEATS);
        verifyFrozenPrimaryInputs(observations, scenarios, PILOT_REPEATS);
        Map<CompressionReplayClients.Variant, VariantSummary> pilot = summarize(observations, PILOT_REPEATS);
        Map<CompressionReplayClients.Variant, String> pilotDecisions = classify(pilot);

        boolean expanded = "CORRECTNESS_PASS".equals(pilotDecisions.get(CompressionReplayClients.Variant.DS_THINKING))
                && "CORRECTNESS_PASS".equals(pilotDecisions.get(CompressionReplayClients.Variant.DS_NON_THINKING));
        if (expanded) {
            executeRepeats(observations, scenarios, factories, 3, 3);
            verifyFrozenPrimaryInputs(observations, scenarios, 3);
        }

        int repeats = expanded ? 3 : PILOT_REPEATS;
        Map<CompressionReplayClients.Variant, VariantSummary> summaries = summarize(observations, repeats);
        Map<CompressionReplayClients.Variant, String> decisions = classify(summaries);
        String winner = winner(summaries, decisions);
        boolean phaseBEligible = winner.startsWith("DS_");

        String runId = "compression-replay-" + startedAt.toString().replace(':', '-')
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        RunResult result = new RunResult(
                runId, gitCommit(), startedAt, OffsetDateTime.now(),
                new Baseline("GPT_COMPATIBLE", configuration.gptModel(),
                        "Spring AI DeepSeekChatModel; model only; temperature/topP/maxTokens unset; default retry template"),
                new DeepSeekWiring(DEEPSEEK_MODEL, true, true,
                        "benchmark-only direct POST /chat/completions"),
                scenarios.size(), repeats, expanded, observations, summaries, decisions,
                winner, phaseBEligible,
                "ESTIMATED_MESSAGE_CHARS_V1; provider usage retained separately when returned");
        Path output = writeArtifacts(result, configuration);
        System.out.println("Compression replay output: " + output.toAbsolutePath());
        System.out.println("Phase A winner: " + winner + "; Phase B eligible: " + phaseBEligible);
    }

    private Path reevaluateArtifact(Path source,
                                    List<CompressionReplayScenarios.Scenario> scenarios,
                                    ReplayConfiguration configuration) throws IOException {
        require(Files.isRegularFile(source), "Compression replay source artifact does not exist");
        RunResult previous = objectMapper.readValue(source.toFile(), RunResult.class);
        Map<String, CompressionReplayScenarios.Scenario> byId = scenarios.stream()
                .collect(Collectors.toMap(CompressionReplayScenarios.Scenario::scenarioId, Function.identity()));
        List<Observation> observations = previous.observations().stream()
                .map(value -> reevaluateObservation(value, Objects.requireNonNull(byId.get(value.scenarioId()),
                        "Unknown source scenario: " + value.scenarioId())))
                .toList();
        verifyFrozenPrimaryInputs(observations, scenarios, previous.repeats());
        Map<CompressionReplayClients.Variant, VariantSummary> summaries = summarize(
                observations, previous.repeats());
        Map<CompressionReplayClients.Variant, String> decisions = classify(summaries);
        String winner = winner(summaries, decisions);
        RunResult result = new RunResult(
                previous.runId(), previous.gitCommit(), previous.startedAt(), previous.endedAt(),
                previous.baseline(), previous.deepSeekWiring(), previous.scenarioCount(), previous.repeats(),
                previous.expandedAfterPilot(), observations, summaries, decisions, winner,
                winner.startsWith("DS_"), previous.tokenMeasurement());
        return writeArtifacts(result, configuration);
    }

    private Observation reevaluateObservation(Observation source,
                                              CompressionReplayScenarios.Scenario scenario) {
        ScenarioInput input = scenarioInput(scenario);
        Validation primary = validateWithCapturedDeltas(scenario, input,
                source.primaryDelta() == null ? List.of() : List.of(source.primaryDelta()));
        Validation merged = primary;
        if (!primary.mergedValid() && source.correctiveDelta() != null) {
            merged = validateWithCapturedDeltas(scenario, input,
                    List.of(source.primaryDelta(), source.correctiveDelta()));
        }
        AssertionResult assertions = assertSemantics(scenario, merged);
        ContextCompressionProperties properties = properties(
                scenario.hardContextBudget(), scenario.maxSingleToolResultTokens());
        int stateTokens = merged.state() == null ? 0 : new EstimatedTokenCounter(properties)
                .countText(MEASUREMENT_MODEL, merged.state().content()).tokens();
        int candidateTokens = merged.state() == null ? 0
                : measureFullCandidateTokens(scenario, input, merged.state());
        boolean candidateFit = merged.state() != null
                && candidateTokens <= scenario.hardContextBudget();
        return new Observation(
                source.scenarioId(), source.category(), source.repeat(), source.variant(), source.provider(),
                source.model(), source.thinkingRequested(), source.thinkingWireVerified(),
                source.primaryPromptSha256(), source.inputChars(), source.inputEstimatedTokens(),
                source.actualInputTokens(), source.primaryLatencyMs(), source.primaryOutputChars(),
                source.primaryOutputEstimatedTokens(), source.actualOutputTokens(),
                source.reasoningContentPresent(), source.reasoningChars(), source.reasoningEstimatedTokens(),
                primary.mergedValid(), assertions.semanticPass(), source.correctiveRetry(),
                source.correctiveLatencyMs(), source.endToEndLatencyMs(), merged.mergedValid(),
                assertions.protectedRetained(), assertions.exactPassed(), assertions.exactTotal(),
                assertions.refsPassed(), assertions.refsTotal(), assertions.relationshipsPassed(),
                assertions.relationshipsTotal(), assertions.noOpPassed(), assertions.noOpApplicable(),
                assertions.illegalRemovalCount(), stateTokens, candidateTokens, candidateFit,
                merged.mergedValid(), source.thinkingDisableEffective(), source.compressorCorrectiveRetryCount(),
                source.primaryDelta(), source.correctiveDelta(), source.failure(), assertions.failures());
    }

    private void executeRepeats(List<Observation> observations,
                                List<CompressionReplayScenarios.Scenario> scenarios,
                                Map<CompressionReplayClients.Variant, ClientFactory> factories,
                                int startRepeat,
                                int endRepeat) {
        List<CompressionReplayClients.Variant> variants = List.of(
                CompressionReplayClients.Variant.GPT_BASELINE,
                CompressionReplayClients.Variant.DS_THINKING,
                CompressionReplayClients.Variant.DS_NON_THINKING);
        for (int repeat = startRepeat; repeat <= endRepeat; repeat++) {
            for (int scenarioIndex = 0; scenarioIndex < scenarios.size(); scenarioIndex++) {
                CompressionReplayScenarios.Scenario scenario = scenarios.get(scenarioIndex);
                int rotation = Math.floorMod(scenarioIndex + repeat - 1, variants.size());
                for (int offset = 0; offset < variants.size(); offset++) {
                    CompressionReplayClients.Variant variant = variants.get((rotation + offset) % variants.size());
                    System.out.printf("Compression replay: scenario=%s repeat=%d variant=%s%n",
                            scenario.scenarioId(), repeat, variant);
                    observations.add(executeScenario(scenario, repeat, factories.get(variant).create()));
                }
            }
        }
    }

    private Observation executeScenario(CompressionReplayScenarios.Scenario scenario,
                                        int repeat,
                                        CompressionReplayClients.Client client) {
        ScenarioInput input = scenarioInput(scenario);
        ContextCompressionProperties properties = properties(scenario.hardContextBudget(),
                scenario.maxSingleToolResultTokens());
        ConversationContextCompressorImpl compressor = compressor(properties, client);
        AtomicReference<AgentLifecycleObservationPublisher.CompressionObservation> captured = new AtomicReference<>();
        ConversationContextCompressor.ContinuationStateCompression runtimeResult;
        try (AgentLifecycleObservationPublisher.Registration ignored =
                     AgentLifecycleObservationPublisher.registerCompression(captured::set)) {
            runtimeResult = compressor.compressCurrentTaskIfNeeded(
                    "replay-" + scenario.scenarioId() + "-" + repeat + "-" + client.variant(),
                    MEASUREMENT_MODEL, input.originalUser(), null, List.of(input.originalUser()),
                    input.protocol(), input.fixedPlanning(), input.existingState());
        }

        AgentLifecycleObservationPublisher.CompressionObservation event = captured.get();
        List<CompressionReplayClients.Invocation> invocations = client.invocations();
        CompressionReplayClients.Invocation primary = invocations.isEmpty() ? null : invocations.get(0);
        CompressionReplayClients.Invocation corrective = invocations.size() < 2 ? null : invocations.get(1);
        String primaryDelta = event == null ? null : event.primaryState();
        String correctiveDelta = event == null ? null : event.correctiveState();

        Validation primaryValidation = validateWithCapturedDeltas(scenario, input,
                primaryDelta == null ? List.of() : List.of(primaryDelta));
        boolean primaryDeltaValid = primaryValidation.mergedValid();
        Validation finalValidation = primaryValidation;
        if (!primaryDeltaValid && correctiveDelta != null) {
            finalValidation = validateWithCapturedDeltas(scenario, input, List.of(primaryDelta, correctiveDelta));
        } else if (primaryDeltaValid && correctiveDelta != null && event != null
                && event.correctivePrompt() != null
                && event.correctivePrompt().startsWith("Compact only the dynamic Open and Next")) {
            finalValidation = applyDynamicCorrective(scenario, input, primaryValidation, correctiveDelta);
        }

        AssertionResult assertions = assertSemantics(scenario, finalValidation);
        int mergedStateTokens = finalValidation.state() == null ? 0
                : new EstimatedTokenCounter(properties).countText(
                MEASUREMENT_MODEL, finalValidation.state().content()).tokens();
        int fullCandidateTokens = finalValidation.state() == null ? 0
                : measureFullCandidateTokens(scenario, input, finalValidation.state());
        boolean candidateFit = finalValidation.state() != null
                && fullCandidateTokens <= scenario.hardContextBudget();
        boolean coverageAdvanced = runtimeResult.compressed()
                && runtimeResult.state().coveredThroughLogicalGroup()
                == scenario.groups().size();
        boolean nonThinkingReasoningAbsent = client.variant() != CompressionReplayClients.Variant.DS_NON_THINKING
                || invocations.stream().noneMatch(CompressionReplayClients.Invocation::reasoningContentPresent);

        return new Observation(
                scenario.scenarioId(), scenario.category(), repeat, client.variant(), client.provider(),
                client.providerModel(), client.thinkingMode().name(), client.thinkingWireVerified(),
                primary == null ? null : primary.promptSha256(),
                primary == null ? 0 : primary.inputChars(), estimate(primary == null ? 0 : primary.inputChars()),
                primary == null ? null : primary.actualInputTokens(),
                primary == null ? 0 : primary.latencyMs(),
                primary == null ? 0 : primary.outputChars(),
                primary == null ? 0 : primary.estimatedOutputTokens(),
                primary == null ? null : primary.actualOutputTokens(),
                primary != null && primary.reasoningContentPresent(),
                primary == null ? 0 : primary.reasoningChars(),
                primary == null ? 0 : primary.estimatedReasoningTokens(),
                primaryDeltaValid, assertions.semanticPass(),
                corrective != null, corrective == null ? 0 : corrective.latencyMs(),
                invocations.stream().mapToLong(CompressionReplayClients.Invocation::latencyMs).sum(),
                finalValidation.mergedValid(), assertions.protectedRetained(),
                assertions.exactPassed(), assertions.exactTotal(),
                assertions.refsPassed(), assertions.refsTotal(),
                assertions.relationshipsPassed(), assertions.relationshipsTotal(),
                assertions.noOpPassed(), assertions.noOpApplicable(), assertions.illegalRemovalCount(),
                mergedStateTokens, fullCandidateTokens, candidateFit, coverageAdvanced,
                nonThinkingReasoningAbsent, runtimeResult.correctiveRetryCount(),
                primaryDelta, correctiveDelta,
                event == null ? "compression observation unavailable" : event.failure(),
                assertions.failures());
    }

    private Validation validateWithCapturedDeltas(CompressionReplayScenarios.Scenario scenario,
                                                   ScenarioInput input,
                                                   List<String> outputs) {
        if (outputs.isEmpty() || outputs.get(0) == null) {
            return Validation.invalid();
        }
        ScriptedSummaryClient scripted = new ScriptedSummaryClient(outputs);
        ConversationContextCompressorImpl validationCompressor = compressor(properties(100_000, 1), scripted);
        ConversationContextCompressor.ContinuationStateCompression result = validationCompressor.compressCurrentTaskIfNeeded(
                "validation-" + scenario.scenarioId(), MEASUREMENT_MODEL,
                input.originalUser(), null, List.of(input.originalUser()), input.protocol(),
                input.fixedPlanning(), input.existingState());
        boolean valid = result.compressed()
                && result.state().coveredThroughLogicalGroup() == scenario.groups().size();
        return new Validation(valid, valid ? result.state() : null);
    }

    private Validation applyDynamicCorrective(CompressionReplayScenarios.Scenario scenario,
                                              ScenarioInput input,
                                              Validation primary,
                                              String correctiveDelta) {
        if (!primary.mergedValid() || primary.state() == null) {
            return Validation.invalid();
        }
        List<ChatMessageDTO> extended = new ArrayList<>(input.protocol());
        extended.addAll(protocol(List.of(new CompressionReplayScenarios.Group(
                "budget-corrective-application",
                List.of(new CompressionReplayScenarios.Call("call-budget-corrective", "knowledgeQuery", "{}")),
                List.of(new CompressionReplayScenarios.Response("call-budget-corrective", "knowledgeQuery",
                        "No new durable facts; apply only the supplied dynamic correction.", false))))));
        ScriptedSummaryClient scripted = new ScriptedSummaryClient(List.of(correctiveDelta));
        ConversationContextCompressorImpl validationCompressor = compressor(properties(100_000, 1), scripted);
        ConversationContextCompressor.ContinuationStateCompression result = validationCompressor.compressCurrentTaskIfNeeded(
                "validation-corrective-" + scenario.scenarioId(), MEASUREMENT_MODEL,
                input.originalUser(), null, List.of(input.originalUser()), extended,
                input.fixedPlanning(), primary.state());
        return result.compressed() ? new Validation(true, result.state()) : Validation.invalid();
    }

    private int measureFullCandidateTokens(CompressionReplayScenarios.Scenario scenario,
                                           ScenarioInput input,
                                           ConversationContextCompressor.ContinuationState state) {
        ContextCompressionProperties measurementProperties = properties(
                scenario.hardContextBudget(), scenario.maxSingleToolResultTokens());
        ConversationContextCompressorImpl measurement = compressor(measurementProperties,
                new ScriptedSummaryClient(List.of()));
        return measurement.checkCurrentTask(
                MEASUREMENT_MODEL, input.originalUser(), null, List.of(input.originalUser()),
                input.protocol(), input.fixedPlanning(), state).contextTokens();
    }

    private AssertionResult assertSemantics(CompressionReplayScenarios.Scenario scenario,
                                            Validation validation) {
        if (!validation.mergedValid() || validation.state() == null
                || !StringUtils.hasText(validation.state().content())) {
            return AssertionResult.invalid(scenario);
        }
        String state = validation.state().content();
        String lower = state.toLowerCase(Locale.ROOT);
        List<String> failures = new ArrayList<>();
        for (String expected : scenario.mustContainKnown()) {
            if (!lower.contains(expected.toLowerCase(Locale.ROOT))) {
                failures.add("known:" + expected);
            }
        }
        int exactPassed = 0;
        for (CompressionReplayScenarios.TextAssertion assertion : scenario.exactValueAssertions()) {
            boolean pass = assertion.anyOf().stream()
                    .anyMatch(value -> assertion.regex()
                            ? Pattern.compile(value, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(state).find()
                            : lower.contains(value.toLowerCase(Locale.ROOT)));
            if (pass) {
                exactPassed++;
            } else {
                failures.add("exact:" + assertion.assertionId());
            }
        }
        int refsPassed = 0;
        for (String ref : scenario.mustContainRefs()) {
            if (state.contains(ref)) {
                refsPassed++;
            } else {
                failures.add("ref:" + ref);
            }
        }
        int relationshipsPassed = 0;
        for (CompressionReplayScenarios.RelationshipAssertion relationship : scenario.relationshipAssertions()) {
            boolean anchors = relationship.allOf().stream()
                    .allMatch(value -> lower.contains(value.toLowerCase(Locale.ROOT)));
            boolean linked = relationship.linkAnyOf().stream()
                    .anyMatch(value -> lower.contains(value.toLowerCase(Locale.ROOT)));
            if (anchors && linked) {
                relationshipsPassed++;
            } else {
                failures.add("relationship:" + relationship.assertionId());
            }
        }
        boolean protectedRetained = scenario.mustPreserveExisting().stream().allMatch(state::contains);
        if (!protectedRetained) {
            failures.add("protected-existing-state");
        }
        int illegalRemovals = (int) scenario.mustNotContain().stream().filter(state::contains).count();
        if (illegalRemovals > 0) {
            failures.add("must-not-contain");
        }
        boolean noOpApplicable = scenario.expectedNoOp();
        boolean noOpPassed = !noOpApplicable || normalize(state).equals(normalize(scenario.existingState()));
        if (!noOpPassed) {
            failures.add("no-op-state-changed");
        }
        return new AssertionResult(failures.isEmpty(), protectedRetained,
                exactPassed, scenario.exactValueAssertions().size(), refsPassed,
                scenario.mustContainRefs().size(), relationshipsPassed,
                scenario.relationshipAssertions().size(), noOpPassed, noOpApplicable,
                illegalRemovals, List.copyOf(failures));
    }

    private Map<CompressionReplayClients.Variant, VariantSummary> summarize(List<Observation> observations,
                                                                            int repeats) {
        Map<CompressionReplayClients.Variant, VariantSummary> result = new EnumMap<>(CompressionReplayClients.Variant.class);
        for (CompressionReplayClients.Variant variant : CompressionReplayClients.Variant.values()) {
            List<Observation> values = observations.stream().filter(value -> value.variant() == variant).toList();
            long expected = (long) CompressionReplayScenarios.all().size() * repeats;
            require(values.size() == expected, "Incomplete observations for " + variant);
            result.put(variant, VariantSummary.from(variant, values));
        }
        return result;
    }

    private Map<CompressionReplayClients.Variant, String> classify(
            Map<CompressionReplayClients.Variant, VariantSummary> summaries) {
        VariantSummary baseline = summaries.get(CompressionReplayClients.Variant.GPT_BASELINE);
        Map<CompressionReplayClients.Variant, String> result = new EnumMap<>(CompressionReplayClients.Variant.class);
        result.put(CompressionReplayClients.Variant.GPT_BASELINE,
                baseline.deltaValidRate() >= 0.90
                        && baseline.scenarioPassRate() >= 0.75
                        && baseline.refExactAccuracy() == 1.0
                        && baseline.protectedStateRetention() == 1.0
                        && baseline.illegalRemovalCount() == 0
                        ? "CORRECTNESS_PASS" : "CORRECTNESS_FAIL");
        for (CompressionReplayClients.Variant variant : List.of(
                CompressionReplayClients.Variant.DS_THINKING,
                CompressionReplayClients.Variant.DS_NON_THINKING)) {
            VariantSummary candidate = summaries.get(variant);
            if (!candidate.wireValid()
                    || variant == CompressionReplayClients.Variant.DS_NON_THINKING
                    && !candidate.thinkingDisableEffective()) {
                result.put(variant, "WIRE_INVALID");
                continue;
            }
            boolean critical = criticalScenarioPassed(candidate.observations(), "S2_EXACT_VALUES")
                    && criticalScenarioPassed(candidate.observations(), "S3_RELATIONSHIP")
                    && criticalScenarioPassed(candidate.observations(), "S4_MULTIPLE_STABLE_REFS");
            boolean pass = candidate.deltaValidRate() + STOCHASTIC_TOLERANCE >= baseline.deltaValidRate()
                    && candidate.scenarioPassRate() + STOCHASTIC_TOLERANCE >= baseline.scenarioPassRate()
                    && candidate.exactValueAccuracy() + STOCHASTIC_TOLERANCE >= baseline.exactValueAccuracy()
                    && candidate.relationshipRecall() + STOCHASTIC_TOLERANCE >= baseline.relationshipRecall()
                    && candidate.noOpAccuracy() + STOCHASTIC_TOLERANCE >= baseline.noOpAccuracy()
                    && candidate.refExactAccuracy() == 1.0
                    && candidate.protectedStateRetention() == 1.0
                    && candidate.illegalRemovalCount() == 0
                    && critical;
            result.put(variant, pass ? "CORRECTNESS_PASS" : "CORRECTNESS_FAIL");
        }
        return result;
    }

    private boolean criticalScenarioPassed(List<Observation> observations, String scenarioId) {
        List<Observation> values = observations.stream()
                .filter(value -> scenarioId.equals(value.scenarioId())).toList();
        return !values.isEmpty() && values.stream().filter(Observation::semanticPass).count() * 2 >= values.size();
    }

    private String winner(Map<CompressionReplayClients.Variant, VariantSummary> summaries,
                          Map<CompressionReplayClients.Variant, String> decisions) {
        VariantSummary baseline = summaries.get(CompressionReplayClients.Variant.GPT_BASELINE);
        VariantSummary nonThinking = summaries.get(CompressionReplayClients.Variant.DS_NON_THINKING);
        VariantSummary thinking = summaries.get(CompressionReplayClients.Variant.DS_THINKING);
        if ("CORRECTNESS_PASS".equals(decisions.get(CompressionReplayClients.Variant.DS_NON_THINKING))
                && nonThinking.meanEndToEndLatencyMs() < baseline.meanEndToEndLatencyMs() * 0.90) {
            return "DS_NON_THINKING";
        }
        if ("CORRECTNESS_PASS".equals(decisions.get(CompressionReplayClients.Variant.DS_THINKING))
                && thinking.meanEndToEndLatencyMs() < baseline.meanEndToEndLatencyMs() * 0.90) {
            return "DS_THINKING";
        }
        return "GPT_BASELINE";
    }

    private void verifyFrozenPrimaryInputs(List<Observation> observations,
                                           List<CompressionReplayScenarios.Scenario> scenarios,
                                           int repeats) {
        for (CompressionReplayScenarios.Scenario scenario : scenarios) {
            List<Observation> values = observations.stream()
                    .filter(value -> scenario.scenarioId().equals(value.scenarioId()) && value.repeat() <= repeats)
                    .toList();
            assertFalse(values.isEmpty());
            assertTrue(values.stream().allMatch(value -> value.primaryPromptSha256() != null),
                    "Every variant must reach the frozen primary request: " + scenario.scenarioId());
            assertEquals(1, values.stream().map(Observation::primaryPromptSha256).distinct().count(),
                    "Primary semantic input drifted across variants/repeats: " + scenario.scenarioId());
            assertEquals(1, values.stream().map(Observation::inputChars).distinct().count(),
                    "Primary input length drifted: " + scenario.scenarioId());
        }
    }

    private void validateScenarioContracts(List<CompressionReplayScenarios.Scenario> scenarios) {
        assertEquals(scenarios.size(), scenarios.stream().map(CompressionReplayScenarios.Scenario::scenarioId)
                .distinct().count());
        for (CompressionReplayScenarios.Scenario scenario : scenarios) {
            assertTrue(StringUtils.hasText(scenario.originalUser()));
            assertFalse(scenario.groups().isEmpty());
            assertEquals(scenario.groups().size() - scenario.coveredThroughLogicalGroupBefore(),
                    scenario.selectedGroupIdentities().size());
            assertTrue(scenario.hardContextBudget() > 0);
            assertFalse(scenario.fixedPlanningMaterial().isEmpty());
            if (scenario.existingState() != null) {
                assertTrue(scenario.existingState().startsWith("Current Task Continuation State"));
            }
        }
    }

    private void verifyCompressionPressure(List<CompressionReplayScenarios.Scenario> scenarios) {
        for (CompressionReplayScenarios.Scenario scenario : scenarios) {
            ScenarioInput input = scenarioInput(scenario);
            ConversationContextCompressor.CompressionCheck check = compressor(
                    properties(scenario.hardContextBudget(), scenario.maxSingleToolResultTokens()),
                    new ScriptedSummaryClient(List.of())).checkCurrentTask(
                    MEASUREMENT_MODEL, input.originalUser(), null, List.of(input.originalUser()),
                    input.protocol(), input.fixedPlanning(), input.existingState());
            assertTrue(check.needed(),
                    "Frozen scenario must require compression: " + scenario.scenarioId());
        }
    }

    private void verifyWireContracts(ReplayConfiguration configuration) {
        assertEquals(DEEPSEEK_MODEL, configuration.deepSeekConfiguredModel());
        assertTrue(CompressionReplayClients.verifyDeepSeekWireContract(
                objectMapper, DEEPSEEK_MODEL, CompressionReplayClients.ThinkingMode.ENABLED, null));
        assertTrue(CompressionReplayClients.verifyDeepSeekWireContract(
                objectMapper, DEEPSEEK_MODEL, CompressionReplayClients.ThinkingMode.DISABLED, null));
    }

    private ScenarioInput scenarioInput(CompressionReplayScenarios.Scenario scenario) {
        ChatMessageDTO user = ChatMessageDTO.builder()
                .id("user-" + scenario.scenarioId())
                .role(ChatMessageDTO.RoleType.USER)
                .content(scenario.originalUser())
                .build();
        List<ChatMessageDTO> fixed = scenario.fixedPlanningMaterial().stream()
                .map(content -> ChatMessageDTO.builder().role(ChatMessageDTO.RoleType.SYSTEM).content(content).build())
                .toList();
        ConversationContextCompressor.ContinuationState state = scenario.existingState() == null
                ? ConversationContextCompressor.ContinuationState.empty()
                : new ConversationContextCompressor.ContinuationState(
                scenario.existingState(), scenario.coveredThroughLogicalGroupBefore(), 1, 1);
        return new ScenarioInput(user, protocol(scenario.groups()), fixed, state);
    }

    private List<ChatMessageDTO> protocol(List<CompressionReplayScenarios.Group> groups) {
        List<ChatMessageDTO> messages = new ArrayList<>();
        for (CompressionReplayScenarios.Group group : groups) {
            List<AssistantMessage.ToolCall> calls = group.calls().stream()
                    .map(call -> new AssistantMessage.ToolCall(
                            call.callId(), "function", call.toolName(), call.arguments()))
                    .toList();
            messages.add(ChatMessageDTO.builder()
                    .id("assistant-" + group.groupId())
                    .role(ChatMessageDTO.RoleType.ASSISTANT)
                    .content("")
                    .metadata(ChatMessageDTO.MetaData.builder().taskId("replay-task").toolCalls(calls).build())
                    .build());
            for (CompressionReplayScenarios.Response response : group.responses()) {
                String body = response.jsonEnvelope()
                        ? writeJsonString(response.body()) : response.body();
                ToolResponseMessage.ToolResponse toolResponse = new ToolResponseMessage.ToolResponse(
                        response.callId(), response.toolName(), body);
                messages.add(ChatMessageDTO.builder()
                        .id("response-" + group.groupId() + "-" + response.callId())
                        .role(ChatMessageDTO.RoleType.TOOL)
                        .content(body)
                        .metadata(ChatMessageDTO.MetaData.builder().taskId("replay-task")
                                .toolResponse(toolResponse).build())
                        .build());
            }
        }
        return List.copyOf(messages);
    }

    private String writeJsonString(String value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private ConversationContextCompressorImpl compressor(ContextCompressionProperties properties,
                                                         ConversationSummaryClient summaryClient) {
        return new ConversationContextCompressorImpl(
                properties, summaryClient, mock(ChatSessionMapper.class), mock(AgentTaskMapper.class),
                objectMapper, new EstimatedTokenCounter(properties));
    }

    private ContextCompressionProperties properties(int hardBudget, int maxSingleToolTokens) {
        ContextCompressionProperties properties = new ContextCompressionProperties();
        properties.setEnabled(true);
        properties.setCompressionTriggerTokens(hardBudget);
        properties.setWorkingContextHardLimitTokens(hardBudget);
        properties.setMaxSingleToolResultTokens(maxSingleToolTokens);
        properties.setMaxHistoryMessages(50);
        properties.setMaxSummaryChars(1_200);
        properties.setCharsPerToken(CHARS_PER_TOKEN);
        return properties;
    }

    private ReplayConfiguration loadConfiguration() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (PropertySource<?> source : loader.load("application", new ClassPathResource("application.yaml"))) {
            environment.getPropertySources().addLast(source);
        }
        FileSystemResource local = new FileSystemResource("application-local.yaml");
        require(local.exists(), "application-local.yaml is required for the real replay experiment");
        List<PropertySource<?>> localSources = loader.load("application-local", local);
        for (int index = localSources.size() - 1; index >= 0; index--) {
            environment.getPropertySources().addFirst(localSources.get(index));
        }
        ReplayConfiguration configuration = new ReplayConfiguration(
                required(environment.getProperty("jchatmind.ai.gpt.compatible.api-key"), "GPT API key"),
                required(environment.getProperty("jchatmind.ai.gpt.compatible.base-url"), "GPT base URL"),
                required(environment.getProperty("jchatmind.ai.gpt.compatible.model"), "GPT model"),
                required(environment.getProperty("jchatmind.ai.deepseek.official.api-key"), "DeepSeek API key"),
                required(environment.getProperty("jchatmind.ai.deepseek.official.base-url"), "DeepSeek base URL"),
                required(environment.getProperty("jchatmind.ai.deepseek.official.model"), "DeepSeek model"));
        require(!"your-api-key".equals(configuration.gptApiKey()), "GPT API key is a placeholder");
        require(!"your-api-key".equals(configuration.deepSeekApiKey()), "DeepSeek API key is a placeholder");
        return configuration;
    }

    private String required(String value, String label) {
        require(StringUtils.hasText(value), label + " is missing");
        return value.trim();
    }

    private Path writeArtifacts(RunResult result, ReplayConfiguration configuration) throws IOException {
        Path directory = Path.of("target", "benchmark", "compression-replay", result.runId());
        Files.createDirectories(directory);
        Path raw = directory.resolve("compression-replay-raw.json");
        Path csv = directory.resolve("compression-replay-results.csv");
        Path report = directory.resolve("compression-replay-report.md");
        objectMapper.writeValue(raw.toFile(), result);
        Files.writeString(csv, csv(result.observations()), StandardCharsets.UTF_8);
        Files.writeString(report, markdown(result), StandardCharsets.UTF_8);
        String rawBody = Files.readString(raw, StandardCharsets.UTF_8);
        String csvBody = Files.readString(csv, StandardCharsets.UTF_8);
        String reportBody = Files.readString(report, StandardCharsets.UTF_8);
        for (String secret : List.of(configuration.gptApiKey(), configuration.deepSeekApiKey())) {
            assertFalse(rawBody.contains(secret), "API key leaked to raw artifact");
            assertFalse(csvBody.contains(secret), "API key leaked to CSV artifact");
            assertFalse(reportBody.contains(secret), "API key leaked to report artifact");
        }
        return directory;
    }

    private String csv(List<Observation> values) {
        StringBuilder out = new StringBuilder("scenario_id,category,repeat,variant,provider,model,thinking_requested,thinking_wire_verified,input_chars,input_estimated_tokens,actual_input_tokens,primary_latency_ms,primary_output_chars,primary_output_estimated_tokens,actual_output_tokens,reasoning_content_present,reasoning_chars,delta_valid,semantic_pass,corrective_retry,corrective_latency_ms,end_to_end_latency_ms,merged_state_valid,protected_state_retained,exact_passed,exact_total,refs_passed,refs_total,relationships_passed,relationships_total,no_op_passed,candidate_state_tokens,full_candidate_tokens,hard_budget_fit,coverage_could_advance,thinking_disable_effective,failure,assertion_failures\n");
        for (Observation value : values) {
            out.append(csvValue(value.scenarioId())).append(',')
                    .append(value.category()).append(',').append(value.repeat()).append(',')
                    .append(value.variant()).append(',').append(value.provider()).append(',')
                    .append(value.model()).append(',').append(value.thinkingRequested()).append(',')
                    .append(value.thinkingWireVerified()).append(',').append(value.inputChars()).append(',')
                    .append(value.inputEstimatedTokens()).append(',').append(nullable(value.actualInputTokens())).append(',')
                    .append(value.primaryLatencyMs()).append(',').append(value.primaryOutputChars()).append(',')
                    .append(value.primaryOutputEstimatedTokens()).append(',').append(nullable(value.actualOutputTokens())).append(',')
                    .append(value.reasoningContentPresent()).append(',').append(value.reasoningChars()).append(',')
                    .append(value.deltaValid()).append(',').append(value.semanticPass()).append(',')
                    .append(value.correctiveRetry()).append(',').append(value.correctiveLatencyMs()).append(',')
                    .append(value.endToEndLatencyMs()).append(',').append(value.mergedStateValid()).append(',')
                    .append(value.protectedStateRetained()).append(',').append(value.exactPassed()).append(',')
                    .append(value.exactTotal()).append(',').append(value.refsPassed()).append(',')
                    .append(value.refsTotal()).append(',').append(value.relationshipsPassed()).append(',')
                    .append(value.relationshipsTotal()).append(',').append(value.noOpPassed()).append(',')
                    .append(value.mergedStateTokens()).append(',').append(value.fullCandidateTokens()).append(',')
                    .append(value.hardBudgetFit()).append(',').append(value.coverageCouldAdvance()).append(',')
                    .append(value.thinkingDisableEffective()).append(',').append(csvValue(value.failure())).append(',')
                    .append(csvValue(String.join(";", value.assertionFailures()))).append('\n');
        }
        return out.toString();
    }

    private String markdown(RunResult result) {
        StringBuilder out = new StringBuilder("# Compression Model Replay Evaluation\n\n")
                .append("- run: `").append(result.runId()).append("`\n")
                .append("- git: `").append(result.gitCommit()).append("`\n")
                .append("- scenarios: ").append(result.scenarioCount()).append("\n")
                .append("- repeats: ").append(result.repeats()).append("\n")
                .append("- GPT model: `").append(result.baseline().model()).append("`\n")
                .append("- DeepSeek model: `").append(result.deepSeekWiring().model()).append("`\n")
                .append("- Phase A winner: `").append(result.winner()).append("`\n")
                .append("- Phase B eligible: ").append(result.phaseBEligible()).append("\n\n")
                .append("## Correctness\n\n")
                .append("| Variant | Decision | Delta valid | Scenario pass | Exact | Refs | Relationship | Protected | No-op | Retry | Fit |\n")
                .append("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (CompressionReplayClients.Variant variant : CompressionReplayClients.Variant.values()) {
            VariantSummary value = result.summaries().get(variant);
            out.append("| ").append(variant).append(" | ").append(result.decisions().get(variant)).append(" | ")
                    .append(percent(value.deltaValidRate())).append(" | ")
                    .append(percent(value.scenarioPassRate())).append(" | ")
                    .append(percent(value.exactValueAccuracy())).append(" | ")
                    .append(percent(value.refExactAccuracy())).append(" | ")
                    .append(percent(value.relationshipRecall())).append(" | ")
                    .append(percent(value.protectedStateRetention())).append(" | ")
                    .append(percent(value.noOpAccuracy())).append(" | ")
                    .append(percent(value.correctiveRetryRate())).append(" | ")
                    .append(percent(value.candidateFitRate())).append(" |\n");
        }
        out.append("\n## Performance\n\n")
                .append("| Variant | P50 primary | P95 primary | P50 E2E | P95 E2E | Mean E2E | Mean output tokens | Median delta tokens | Mean state tokens |\n")
                .append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (CompressionReplayClients.Variant variant : CompressionReplayClients.Variant.values()) {
            VariantSummary value = result.summaries().get(variant);
            out.append("| ").append(variant).append(" | ")
                    .append(value.p50PrimaryLatencyMs()).append("ms | ")
                    .append(value.p95PrimaryLatencyMs()).append("ms | ")
                    .append(value.p50EndToEndLatencyMs()).append("ms | ")
                    .append(value.p95EndToEndLatencyMs()).append("ms | ")
                    .append(String.format(Locale.ROOT, "%.1fms", value.meanEndToEndLatencyMs())).append(" | ")
                    .append(String.format(Locale.ROOT, "%.1f", value.meanOutputTokens())).append(" | ")
                    .append(value.medianValidDeltaTokens()).append(" | ")
                    .append(String.format(Locale.ROOT, "%.1f", value.meanStateTokens())).append(" |\n");
        }
        out.append("\n## Scenario Matrix\n\n")
                .append("| Scenario | Variant | Repeat | Delta Valid | Semantic PASS | Retry | State Tokens | Candidate Fit | Latency |\n")
                .append("| --- | --- | ---: | --- | --- | --- | ---: | --- | ---: |\n");
        result.observations().stream().sorted(Comparator
                        .comparing(Observation::scenarioId)
                        .thenComparingInt(Observation::repeat)
                        .thenComparing(value -> value.variant().ordinal()))
                .forEach(value -> out.append("| ").append(value.scenarioId()).append(" | ")
                        .append(value.variant()).append(" | ").append(value.repeat()).append(" | ")
                        .append(value.deltaValid()).append(" | ").append(value.semanticPass()).append(" | ")
                        .append(value.correctiveRetry()).append(" | ").append(value.mergedStateTokens()).append(" | ")
                        .append(value.hardBudgetFit()).append(" | ").append(value.endToEndLatencyMs()).append("ms |\n"));
        return out.toString();
    }

    private String gitCommit() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.waitFor() != 0 || !StringUtils.hasText(output)) {
                return "UNAVAILABLE";
            }
            return output;
        } catch (Exception error) {
            return "UNAVAILABLE";
        }
    }

    private int estimate(int chars) {
        return (int) Math.ceil((double) chars / CHARS_PER_TOKEN);
    }

    private String normalize(String value) {
        return value == null ? "" : value.strip().replace("\r\n", "\n");
    }

    private String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100.0);
    }

    private String csvValue(String value) {
        if (value == null) {
            return "";
        }
        return '"' + value.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + '"';
    }

    private String nullable(Object value) {
        return value == null ? "" : value.toString();
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    static boolean enabled() {
        return Boolean.getBoolean("compression.replay.enabled");
    }

    private interface ClientFactory {
        CompressionReplayClients.Client create();
    }

    private record ReplayConfiguration(
            String gptApiKey,
            String gptBaseUrl,
            String gptModel,
            String deepSeekApiKey,
            String deepSeekBaseUrl,
            String deepSeekConfiguredModel) {
    }

    private record ScenarioInput(
            ChatMessageDTO originalUser,
            List<ChatMessageDTO> protocol,
            List<ChatMessageDTO> fixedPlanning,
            ConversationContextCompressor.ContinuationState existingState) {
    }

    private record Validation(boolean mergedValid,
                              ConversationContextCompressor.ContinuationState state) {
        static Validation invalid() {
            return new Validation(false, null);
        }
    }

    private record AssertionResult(
            boolean semanticPass,
            boolean protectedRetained,
            int exactPassed,
            int exactTotal,
            int refsPassed,
            int refsTotal,
            int relationshipsPassed,
            int relationshipsTotal,
            boolean noOpPassed,
            boolean noOpApplicable,
            int illegalRemovalCount,
            List<String> failures) {
        static AssertionResult invalid(CompressionReplayScenarios.Scenario scenario) {
            return new AssertionResult(false, false, 0, scenario.exactValueAssertions().size(),
                    0, scenario.mustContainRefs().size(), 0, scenario.relationshipAssertions().size(),
                    false, scenario.expectedNoOp(), 0, List.of("merged-state-invalid"));
        }
    }

    private static final class ScriptedSummaryClient implements ConversationSummaryClient {
        private final List<String> outputs;
        private int index;

        private ScriptedSummaryClient(List<String> outputs) {
            this.outputs = new ArrayList<>(outputs);
        }

        @Override
        public String summarize(String model, String prompt) {
            return index < outputs.size() ? outputs.get(index++) : "";
        }
    }

    record Observation(
            String scenarioId,
            String category,
            int repeat,
            CompressionReplayClients.Variant variant,
            String provider,
            String model,
            String thinkingRequested,
            boolean thinkingWireVerified,
            String primaryPromptSha256,
            int inputChars,
            int inputEstimatedTokens,
            Integer actualInputTokens,
            long primaryLatencyMs,
            int primaryOutputChars,
            int primaryOutputEstimatedTokens,
            Integer actualOutputTokens,
            boolean reasoningContentPresent,
            int reasoningChars,
            int reasoningEstimatedTokens,
            boolean deltaValid,
            boolean semanticPass,
            boolean correctiveRetry,
            long correctiveLatencyMs,
            long endToEndLatencyMs,
            boolean mergedStateValid,
            boolean protectedStateRetained,
            int exactPassed,
            int exactTotal,
            int refsPassed,
            int refsTotal,
            int relationshipsPassed,
            int relationshipsTotal,
            boolean noOpPassed,
            boolean noOpApplicable,
            int illegalRemovalCount,
            int mergedStateTokens,
            int fullCandidateTokens,
            boolean hardBudgetFit,
            boolean coverageCouldAdvance,
            boolean thinkingDisableEffective,
            int compressorCorrectiveRetryCount,
            String primaryDelta,
            String correctiveDelta,
            String failure,
            List<String> assertionFailures) {
    }

    record VariantSummary(
            CompressionReplayClients.Variant variant,
            double deltaValidRate,
            double scenarioPassRate,
            double exactValueAccuracy,
            double refExactAccuracy,
            double relationshipRecall,
            double protectedStateRetention,
            double noOpAccuracy,
            double correctiveRetryRate,
            double candidateFitRate,
            long p50PrimaryLatencyMs,
            long p95PrimaryLatencyMs,
            long p50EndToEndLatencyMs,
            long p95EndToEndLatencyMs,
            double meanEndToEndLatencyMs,
            double correctiveRetryCostMs,
            double meanOutputTokens,
            long medianValidDeltaTokens,
            double meanStateTokens,
            boolean wireValid,
            boolean thinkingDisableEffective,
            int illegalRemovalCount,
            List<Observation> observations) {

        static VariantSummary from(CompressionReplayClients.Variant variant, List<Observation> observations) {
            int size = observations.size();
            long exactTotal = observations.stream().mapToLong(Observation::exactTotal).sum();
            long refsTotal = observations.stream().mapToLong(Observation::refsTotal).sum();
            long relationshipsTotal = observations.stream().mapToLong(Observation::relationshipsTotal).sum();
            List<Observation> noOps = observations.stream().filter(Observation::noOpApplicable).toList();
            List<Long> primaryLatencies = observations.stream().map(Observation::primaryLatencyMs).sorted().toList();
            List<Long> endToEndLatencies = observations.stream().map(Observation::endToEndLatencyMs).sorted().toList();
            List<Long> validDeltaTokens = observations.stream().filter(Observation::deltaValid)
                    .map(value -> (long) value.primaryOutputEstimatedTokens()).sorted().toList();
            return new VariantSummary(
                    variant,
                    rate(observations.stream().filter(Observation::deltaValid).count(), size),
                    rate(observations.stream().filter(Observation::semanticPass).count(), size),
                    rate(observations.stream().mapToLong(Observation::exactPassed).sum(), exactTotal),
                    rate(observations.stream().mapToLong(Observation::refsPassed).sum(), refsTotal),
                    rate(observations.stream().mapToLong(Observation::relationshipsPassed).sum(), relationshipsTotal),
                    rate(observations.stream().filter(Observation::protectedStateRetained).count(), size),
                    rate(noOps.stream().filter(Observation::noOpPassed).count(), noOps.size()),
                    rate(observations.stream().filter(Observation::correctiveRetry).count(), size),
                    rate(observations.stream().filter(Observation::hardBudgetFit).count(), size),
                    percentile(primaryLatencies, 0.50), percentile(primaryLatencies, 0.95),
                    percentile(endToEndLatencies, 0.50), percentile(endToEndLatencies, 0.95),
                    observations.stream().mapToLong(Observation::endToEndLatencyMs).average().orElse(0),
                    observations.stream().mapToLong(Observation::correctiveLatencyMs).average().orElse(0),
                    observations.stream().mapToInt(Observation::primaryOutputEstimatedTokens).average().orElse(0),
                    percentile(validDeltaTokens, 0.50),
                    observations.stream().mapToInt(Observation::mergedStateTokens).average().orElse(0),
                    variant == CompressionReplayClients.Variant.GPT_BASELINE
                            || observations.stream().allMatch(Observation::thinkingWireVerified),
                    variant != CompressionReplayClients.Variant.DS_NON_THINKING
                            || observations.stream().allMatch(Observation::thinkingDisableEffective),
                    observations.stream().mapToInt(Observation::illegalRemovalCount).sum(),
                    List.copyOf(observations));
        }

        private static double rate(long numerator, long denominator) {
            return denominator == 0 ? 1.0 : (double) numerator / denominator;
        }

        private static long percentile(List<Long> sorted, double percentile) {
            if (sorted.isEmpty()) {
                return 0;
            }
            int index = Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1);
            return sorted.get(index);
        }
    }

    record Baseline(String provider, String model, String options) {
    }

    record DeepSeekWiring(String model,
                          boolean thinkingEnabledWireVerified,
                          boolean thinkingDisabledWireVerified,
                          String implementationPath) {
    }

    record RunResult(
            String runId,
            String gitCommit,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt,
            Baseline baseline,
            DeepSeekWiring deepSeekWiring,
            int scenarioCount,
            int repeats,
            boolean expandedAfterPilot,
            List<Observation> observations,
            Map<CompressionReplayClients.Variant, VariantSummary> summaries,
            Map<CompressionReplayClients.Variant, String> decisions,
            String winner,
            boolean phaseBEligible,
            String tokenMeasurement) {
    }
}
