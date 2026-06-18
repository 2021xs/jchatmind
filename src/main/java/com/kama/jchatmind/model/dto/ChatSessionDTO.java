package com.kama.jchatmind.model.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
public class ChatSessionDTO {
    private String id;

    private String agentId;

    private String title;

    private MetaData metadata;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Data
    public static class MetaData {
        private String channel;
        private String repoId;
        private String source;
        private String contextSummary;
        private String contextSummaryLastMessageId;
        private LocalDateTime contextSummaryUpdatedAt;

        @JsonIgnore
        private Map<String, Object> extra = new LinkedHashMap<>();

        @JsonAnySetter
        public void putExtra(String key, Object value) {
            extra.put(key, value);
        }

        @JsonAnyGetter
        public Map<String, Object> getExtra() {
            return extra;
        }
    }
}
