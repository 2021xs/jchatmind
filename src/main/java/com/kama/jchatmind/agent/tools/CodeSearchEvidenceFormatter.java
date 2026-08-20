package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.model.dto.CodeSearchResult;
import org.springframework.util.StringUtils;

import java.util.List;

final class CodeSearchEvidenceFormatter {

    String format(List<CodeSearchResult> evidence) {
        StringBuilder out = new StringBuilder("Selected code evidence:\n");
        for (int i = 0; i < evidence.size(); i++) {
            if (i > 0) {
                out.append('\n');
            }
            appendEvidence(out, i + 1, evidence.get(i));
        }
        return out.toString();
    }

    private void appendEvidence(StringBuilder out, int index, CodeSearchResult evidence) {
        out.append('\n').append('[').append(index).append("]\n");
        appendField(out, "file", evidence.getFilePath());
        appendField(out, "symbol", evidence.getSymbolName());
        appendField(out, "type", evidence.getChunkType());
        appendField(out, "lines", lineRange(evidence));
        appendField(out, "api", api(evidence));
        if (StringUtils.hasText(evidence.getContentPreview())) {
            out.append("\nsnippet:\n").append(evidence.getContentPreview()).append('\n');
        }
    }

    private void appendField(StringBuilder out, String name, String value) {
        if (StringUtils.hasText(value)) {
            out.append(name).append(": ").append(value).append('\n');
        }
    }

    private String lineRange(CodeSearchResult evidence) {
        if (evidence.getStartLine() == null) {
            return null;
        }
        if (evidence.getEndLine() == null) {
            return evidence.getStartLine().toString();
        }
        return evidence.getStartLine() + "-" + evidence.getEndLine();
    }

    private String api(CodeSearchResult evidence) {
        boolean hasMethod = StringUtils.hasText(evidence.getHttpMethod());
        boolean hasPath = StringUtils.hasText(evidence.getApiPath());
        if (!hasMethod && !hasPath) {
            return null;
        }
        if (!hasMethod) {
            return evidence.getApiPath();
        }
        if (!hasPath) {
            return evidence.getHttpMethod();
        }
        return evidence.getHttpMethod() + " " + evidence.getApiPath();
    }
}
