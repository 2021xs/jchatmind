package com.kama.jchatmind.service;

import com.kama.jchatmind.model.dto.RagSearchResult;

import java.util.List;

public interface RagService {
    float[] embed(String text);

    List<String> similaritySearch(String kbId, String title);

    List<RagSearchResult> similaritySearchWithMetadata(String kbId, String query);
}
