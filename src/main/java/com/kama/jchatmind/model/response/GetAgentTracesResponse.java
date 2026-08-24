package com.kama.jchatmind.model.response;

import com.kama.jchatmind.model.vo.AgentTaskTraceVO;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class GetAgentTracesResponse {
    private List<AgentTaskTraceVO> traces;
}
