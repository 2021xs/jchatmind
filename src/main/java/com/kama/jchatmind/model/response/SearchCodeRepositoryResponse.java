package com.kama.jchatmind.model.response;

import com.kama.jchatmind.model.dto.CodeSearchResult;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SearchCodeRepositoryResponse {
    private CodeSearchResult[] results;
}
