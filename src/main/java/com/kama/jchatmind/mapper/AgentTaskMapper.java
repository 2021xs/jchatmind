package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.AgentTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AgentTaskMapper {
    int insert(AgentTask agentTask);

    AgentTask selectById(String id);

    int updateById(AgentTask agentTask);

    int updateTerminalIfRunning(AgentTask agentTask);

    int bindUserMessage(@Param("taskId") String taskId,
                        @Param("userMessageId") String userMessageId);

    AgentTask selectActiveRunningBySessionId(@Param("sessionId") String sessionId,
                                             @Param("staleBefore") LocalDateTime staleBefore);

    List<AgentTask> selectStaleRunningBefore(LocalDateTime staleBefore);

    List<AgentTask> selectRecent(@Param("limit") int limit);

    List<AgentTask> selectRecentBySessionId(@Param("sessionId") String sessionId,
                                            @Param("limit") int limit);

    int deleteBySessionId(@Param("sessionId") String sessionId);
}
