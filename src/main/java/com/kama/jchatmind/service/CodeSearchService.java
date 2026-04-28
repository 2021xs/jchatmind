package com.kama.jchatmind.service;

import com.kama.jchatmind.model.dto.CodeSearchResult;

import java.util.List;

public interface CodeSearchService {
    List<CodeSearchResult> search(String repoId, String query, int topK);
}
