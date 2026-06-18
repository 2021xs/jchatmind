package com.kama.jchatmind.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.model.common.ChatSessionChannel;
import com.kama.jchatmind.model.dto.ChatSessionDTO;
import com.kama.jchatmind.model.entity.ChatSession;
import com.kama.jchatmind.model.request.CreateChatSessionRequest;
import com.kama.jchatmind.model.request.UpdateChatSessionRequest;
import com.kama.jchatmind.model.vo.ChatSessionVO;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@AllArgsConstructor
public class ChatSessionConverter {

    private final ObjectMapper objectMapper;

    public ChatSession toEntity(ChatSessionDTO chatSessionDTO) throws JsonProcessingException {
        Assert.notNull(chatSessionDTO, "ChatSessionDTO cannot be null");

        return ChatSession.builder()
                .id(chatSessionDTO.getId())
                .agentId(chatSessionDTO.getAgentId())
                .title(chatSessionDTO.getTitle())
                .metadata(chatSessionDTO.getMetadata() != null 
                        ? objectMapper.writeValueAsString(chatSessionDTO.getMetadata()) 
                        : null)
                .createdAt(chatSessionDTO.getCreatedAt())
                .updatedAt(chatSessionDTO.getUpdatedAt())
                .build();
    }

    public ChatSessionDTO toDTO(ChatSession chatSession) throws JsonProcessingException {
        Assert.notNull(chatSession, "ChatSession cannot be null");

        return ChatSessionDTO.builder()
                .id(chatSession.getId())
                .agentId(chatSession.getAgentId())
                .title(chatSession.getTitle())
                .metadata(StringUtils.hasText(chatSession.getMetadata())
                        ? objectMapper.readValue(chatSession.getMetadata(), ChatSessionDTO.MetaData.class)
                        : null)
                .createdAt(chatSession.getCreatedAt())
                .updatedAt(chatSession.getUpdatedAt())
                .build();
    }

    public ChatSessionVO toVO(ChatSessionDTO dto) {
        return ChatSessionVO.builder()
                .id(dto.getId())
                .agentId(dto.getAgentId())
                .title(dto.getTitle())
                .channel(resolveChannel(dto.getMetadata()))
                .repoId(dto.getMetadata() == null ? null : dto.getMetadata().getRepoId())
                .createdAt(dto.getCreatedAt())
                .updatedAt(dto.getUpdatedAt())
                .build();
    }

    public ChatSessionVO toVO(ChatSession chatSession) throws JsonProcessingException {
        return toVO(toDTO(chatSession));
    }

    public ChatSessionDTO toDTO(CreateChatSessionRequest request) {
        Assert.notNull(request, "CreateChatSessionRequest cannot be null");
        Assert.notNull(request.getAgentId(), "AgentId cannot be null");

        return ChatSessionDTO.builder()
                .agentId(request.getAgentId())
                .title(request.getTitle())
                .metadata(toMetadata(request))
                .build();
    }

    public void updateDTOFromRequest(ChatSessionDTO dto, UpdateChatSessionRequest request) {
        Assert.notNull(dto, "ChatSessionDTO cannot be null");
        Assert.notNull(request, "UpdateChatSessionRequest cannot be null");

        if (request.getTitle() != null) {
            dto.setTitle(request.getTitle());
        }
    }

    private ChatSessionDTO.MetaData toMetadata(CreateChatSessionRequest request) {
        if (!StringUtils.hasText(request.getChannel())
                && !StringUtils.hasText(request.getRepoId())
                && CollectionUtils.isEmpty(request.getMetadata())) {
            return null;
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        if (!CollectionUtils.isEmpty(request.getMetadata())) {
            merged.putAll(request.getMetadata());
        }
        if (StringUtils.hasText(request.getChannel())) {
            merged.put("channel", ChatSessionChannel.normalize(request.getChannel()));
        } else if (merged.get("channel") instanceof String metadataChannel
                && StringUtils.hasText(metadataChannel)) {
            merged.put("channel", ChatSessionChannel.normalize(metadataChannel));
        }
        if (StringUtils.hasText(request.getRepoId())) {
            merged.put("repoId", request.getRepoId().trim());
        }
        if (ChatSessionChannel.WEB_CONSOLE.name().equals(merged.get("channel"))) {
            merged.putIfAbsent("source", "web-console");
        }
        return objectMapper.convertValue(merged, ChatSessionDTO.MetaData.class);
    }

    private String resolveChannel(ChatSessionDTO.MetaData metadata) {
        if (metadata == null || !StringUtils.hasText(metadata.getChannel())) {
            return ChatSessionChannel.LEGACY.name();
        }
        return ChatSessionChannel.normalize(metadata.getChannel());
    }
}
