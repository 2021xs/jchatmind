package com.kama.jchatmind.service;

import com.kama.jchatmind.model.dto.CodeSearchResult;
import com.kama.jchatmind.model.dto.CodeSearchExecutionResult;

import java.util.List;

public interface CodeSearchService {
    List<CodeSearchResult> search(String repoId, String query, int topK);

    CodeSearchExecutionResult searchWithTrace(String repoId, String query, int topK);
}
