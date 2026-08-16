package com.kama.jchatmind.service;

import com.kama.jchatmind.model.dto.CodeAnswerEvidenceResult;
import com.kama.jchatmind.model.dto.CodeRagExecutionResult;

public interface CodeRagAnswerEvidenceService {
    CodeAnswerEvidenceResult retrieve(String repoId, String query);

    CodeRagExecutionResult execute(String repoId, String query);
}
