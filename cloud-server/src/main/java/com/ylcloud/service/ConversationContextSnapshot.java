package com.ylcloud.service;

import com.ylcloud.DTO.RagChatMessageDTO;

import java.time.LocalDateTime;
import java.util.List;

public record ConversationContextSnapshot(
        int version,
        Long sessionId,
        Long userId,
        Long assistantMessageId,
        Long sourceMessageId,
        List<Long> messageIds,
        List<RagChatMessageDTO> history,
        Integer historyTokens,
        Integer summaryTokens,
        Integer totalTokens,
        Integer summaryVersion,
        List<Long> knowledgeChunkIds,
        List<Long> memoryIds,
        Integer memoryTokens,
        String strategy,
        LocalDateTime createdAt) {

    public ConversationContextSnapshot(int version, Long sessionId, Long userId, Long assistantMessageId,
                                       Long sourceMessageId, List<Long> messageIds, List<RagChatMessageDTO> history,
                                       Integer historyTokens, Integer summaryTokens, Integer totalTokens,
                                       Integer summaryVersion, List<Long> knowledgeChunkIds, String strategy,
                                       LocalDateTime createdAt) {
        this(version,sessionId,userId,assistantMessageId,sourceMessageId,messageIds,history,historyTokens,
                summaryTokens,totalTokens,summaryVersion,knowledgeChunkIds,List.of(),0,strategy,createdAt);
    }
}
