package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.CodeFile;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CodeFileMapper {
    int insert(CodeFile codeFile);

    int deleteByRepoId(String repoId);
}
