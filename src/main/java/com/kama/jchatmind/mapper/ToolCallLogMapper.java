package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.ToolCallLog;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ToolCallLogMapper {
    int insert(ToolCallLog toolCallLog);

    ToolCallLog selectById(String id);

    int updateById(ToolCallLog toolCallLog);

    List<ToolCallLog> selectRunningByTaskId(String taskId);
}
