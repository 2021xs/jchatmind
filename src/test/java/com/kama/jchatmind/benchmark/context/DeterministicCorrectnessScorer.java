package com.kama.jchatmind.benchmark.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class DeterministicCorrectnessScorer {

    ContextLifecycleBenchmarkResult.CorrectnessMetrics score(
            ContextLifecycleBenchmarkCase benchmarkCase, String answer) {
        String normalized = normalize(answer);
        List<ContextLifecycleBenchmarkResult.FactCheck> critical = factChecks(
                ContextLifecycleBenchmarkCase.safe(benchmarkCase.expectedCriticalFacts), normalized);
        List<ContextLifecycleBenchmarkResult.FactCheck> supporting = factChecks(
                ContextLifecycleBenchmarkCase.safe(benchmarkCase.expectedSupportingFacts), normalized);
        supporting = new ArrayList<>(supporting);
        supporting.addAll(referenceChecks(ContextLifecycleBenchmarkCase.safe(benchmarkCase.expectedRefs), normalized));
        List<ContextLifecycleBenchmarkResult.FactCheck> exact = exactChecks(
                ContextLifecycleBenchmarkCase.safe(benchmarkCase.exactValues), answer);
        List<ContextLifecycleBenchmarkResult.FactCheck> forbidden = forbiddenChecks(
                ContextLifecycleBenchmarkCase.safe(benchmarkCase.forbiddenClaims), normalized);
        return new ContextLifecycleBenchmarkResult.CorrectnessMetrics(
                recall(critical), recall(exact),
                (int) forbidden.stream().filter(ContextLifecycleBenchmarkResult.FactCheck::matched).count(),
                critical, List.copyOf(supporting), exact, forbidden, "NOT_USED_DETERMINISTIC_ONLY");
    }

    private List<ContextLifecycleBenchmarkResult.FactCheck> factChecks(
            List<ContextLifecycleBenchmarkCase.FactExpectation> facts, String answer) {
        List<ContextLifecycleBenchmarkResult.FactCheck> checks = new ArrayList<>();
        for (ContextLifecycleBenchmarkCase.FactExpectation fact : facts) {
            boolean allMatched = ContextLifecycleBenchmarkCase.safe(fact.allOf).stream()
                    .map(this::normalize).allMatch(answer::contains);
            boolean anyMatched = ContextLifecycleBenchmarkCase.safe(fact.anyOf).isEmpty()
                    || ContextLifecycleBenchmarkCase.safe(fact.anyOf).stream()
                    .map(this::normalize).anyMatch(answer::contains);
            boolean matched = allMatched && anyMatched;
            String expected = "allOf=" + ContextLifecycleBenchmarkCase.safe(fact.allOf)
                    + ", anyOf=" + ContextLifecycleBenchmarkCase.safe(fact.anyOf);
            checks.add(new ContextLifecycleBenchmarkResult.FactCheck(
                    fact.id, expected, matched, matched ? "deterministic normalized text match" : "not matched"));
        }
        return checks;
    }

    private List<ContextLifecycleBenchmarkResult.FactCheck> exactChecks(
            List<ContextLifecycleBenchmarkCase.ExactValueExpectation> values, String answer) {
        String safe = answer == null ? "" : answer;
        List<ContextLifecycleBenchmarkResult.FactCheck> checks = new ArrayList<>();
        for (ContextLifecycleBenchmarkCase.ExactValueExpectation value : values) {
            Pattern boundary = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_])"
                    + Pattern.quote(value.value) + "(?![\\p{L}\\p{N}_])");
            boolean matched = boundary.matcher(safe).find();
            checks.add(new ContextLifecycleBenchmarkResult.FactCheck(
                    value.id, value.value, matched, matched ? "exact boundary match" : "not matched"));
        }
        return checks;
    }

    private List<ContextLifecycleBenchmarkResult.FactCheck> forbiddenChecks(
            List<String> claims, String answer) {
        List<ContextLifecycleBenchmarkResult.FactCheck> checks = new ArrayList<>();
        for (int index = 0; index < claims.size(); index++) {
            String claim = claims.get(index);
            boolean present = answer.contains(normalize(claim));
            checks.add(new ContextLifecycleBenchmarkResult.FactCheck(
                    "forbidden-" + (index + 1), claim, present,
                    present ? "forbidden text present" : "not present"));
        }
        return checks;
    }

    private List<ContextLifecycleBenchmarkResult.FactCheck> referenceChecks(
            List<String> references, String answer) {
        List<ContextLifecycleBenchmarkResult.FactCheck> checks = new ArrayList<>();
        for (int index = 0; index < references.size(); index++) {
            String reference = references.get(index);
            boolean matched = answer.contains(normalize(reference));
            checks.add(new ContextLifecycleBenchmarkResult.FactCheck(
                    "expected-ref-" + (index + 1), reference, matched,
                    matched ? "deterministic normalized reference match" : "not matched"));
        }
        return checks;
    }

    private double recall(List<ContextLifecycleBenchmarkResult.FactCheck> checks) {
        if (checks.isEmpty()) {
            return 1.0;
        }
        return checks.stream().filter(ContextLifecycleBenchmarkResult.FactCheck::matched).count()
                / (double) checks.size();
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
