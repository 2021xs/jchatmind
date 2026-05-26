package com.kama.jchatmind.service;

import com.kama.jchatmind.model.dto.ParsedCodeFile;
import com.kama.jchatmind.model.entity.CodeChunk;

public interface CodeChunkEmbeddingTextBuilder {
    String build(ParsedCodeFile parsed, CodeChunk chunk);
}
