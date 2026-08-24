package com.kama.jchatmind.agent;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Deterministic safety/format contract for a fully aggregated Final attempt. */
public final class FinalOutputValidator {

    private static final Set<String> ALLOWED_FINISH_REASONS = Set.of("STOP", "COMPLETE", "COMPLETED", "END_TURN");
    private static final List<String> INTERNAL_MARKERS = List.of(
            "[final_evidence_batch]",
            FinalContextCompiler.EVIDENCE_CONTAINER_START,
            FinalContextCompiler.EVIDENCE_CONTAINER_END,
            "<evidence_batch",
            "</evidence_batch>",
            "[end evidence",
            "code evidence novelty:",
            "code_search_no_novelty_guard");
    private static final Pattern LEGACY_EVIDENCE_CONTINUATION = Pattern.compile(
            "(?is)^\\s*Batch\\s*:\\s*\\d+\\b(?=.*\\bSource-Tool\\s*:)(?=.*\\bContent-Characters\\s*:)");

    public ValidationResult validate(String text, boolean rawToolCallPresent, String providerFinishReason) {
        List<ViolationCode> violations = new ArrayList<>();
        if (!StringUtils.hasText(text)) {
            violations.add(ViolationCode.EMPTY_TEXT);
        }
        if (rawToolCallPresent) {
            violations.add(ViolationCode.UNEXPECTED_TOOL_CALL);
        }
        if (!StringUtils.hasText(providerFinishReason)
                || !ALLOWED_FINISH_REASONS.contains(providerFinishReason.trim().toUpperCase(Locale.ROOT))) {
            violations.add(ViolationCode.INVALID_FINISH_REASON);
        }
        if (StringUtils.hasText(text)) {
            String normalized = text.toLowerCase(Locale.ROOT);
            if (INTERNAL_MARKERS.stream().anyMatch(normalized::contains)) {
                violations.add(ViolationCode.INTERNAL_MARKER_LEAKAGE);
            }
            if (LEGACY_EVIDENCE_CONTINUATION.matcher(text).find()) {
                violations.add(ViolationCode.EVIDENCE_BATCH_CONTINUATION);
            }
            if ((normalized.contains("returnedevidencecount=") && normalized.contains("newevidencecount="))
                    || (normalized.contains("toolcallid=") && normalized.contains("responsedata="))) {
                violations.add(ViolationCode.INTERNAL_PROTOCOL_OUTPUT);
            }
        }
        List<ViolationCode> distinct = violations.stream().distinct().toList();
        return new ValidationResult(distinct.isEmpty(), distinct,
                distinct.isEmpty() ? "PASS" : "violations=" + distinct);
    }

    public enum ViolationCode {
        EMPTY_TEXT,
        UNEXPECTED_TOOL_CALL,
        INVALID_FINISH_REASON,
        INTERNAL_MARKER_LEAKAGE,
        EVIDENCE_BATCH_CONTINUATION,
        INTERNAL_PROTOCOL_OUTPUT
    }

    public record ValidationResult(boolean valid,
                                   List<ViolationCode> violationCodes,
                                   String safeDiagnostic) {
        public ValidationResult {
            violationCodes = List.copyOf(violationCodes);
        }
    }
}
