package com.kama.jchatmind.model.response;

import com.kama.jchatmind.model.entity.CodeRepository;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GetCodeRepositoriesResponse {
    private CodeRepository[] repositories;
}
