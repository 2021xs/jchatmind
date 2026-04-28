package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.dto.CodeSearchResult;
import com.kama.jchatmind.model.entity.CodeChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CodeChunkMapper {
    int insert(CodeChunk codeChunk);

    int deleteByRepoId(String repoId);

    List<CodeSearchResult> similaritySearch(
            @Param("repoId") String repoId,
            @Param("vectorLiteral") String vectorLiteral,
            @Param("limit") int limit
    );
}
