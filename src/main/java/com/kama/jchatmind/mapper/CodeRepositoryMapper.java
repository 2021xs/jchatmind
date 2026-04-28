package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.CodeRepository;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CodeRepositoryMapper {
    int insert(CodeRepository codeRepository);

    CodeRepository selectById(String id);

    CodeRepository selectExisting(@Param("name") String name, @Param("rootPath") String rootPath);

    List<CodeRepository> selectAll();

    int updateById(CodeRepository codeRepository);
}
