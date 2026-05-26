package com.kama.jchatmind.service;

import com.kama.jchatmind.model.dto.CodeAnswerEvidenceResult;

public interface CodeRagAnswerEvidenceService {
    CodeAnswerEvidenceResult retrieve(String repoId, String query);
}
