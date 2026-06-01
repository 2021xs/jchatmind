package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.FeishuAgentSessionBinding;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FeishuAgentSessionBindingMapper {

    FeishuAgentSessionBinding selectByFeishuChatId(String feishuChatId);

    int insert(FeishuAgentSessionBinding binding);

    int upsertActiveSession(FeishuAgentSessionBinding binding);
}
