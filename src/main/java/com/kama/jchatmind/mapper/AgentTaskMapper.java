package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.AgentTask;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AgentTaskMapper {
    int insert(AgentTask agentTask);

    AgentTask selectById(String id);

    int updateById(AgentTask agentTask);

    List<AgentTask> selectStaleRunningBefore(LocalDateTime staleBefore);
}
