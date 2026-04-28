package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.AgentStep;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentStepMapper {
    int insert(AgentStep agentStep);

    AgentStep selectById(String id);

    int updateById(AgentStep agentStep);
}
