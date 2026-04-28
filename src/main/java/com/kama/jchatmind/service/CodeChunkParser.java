package com.kama.jchatmind.service;

import com.kama.jchatmind.model.dto.ParsedCodeFile;

import java.nio.file.Path;

public interface CodeChunkParser {
    ParsedCodeFile parse(Path rootPath, Path filePath);
}
