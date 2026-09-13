package com.ylcloud.service.knowledge.pipeline;

import com.ylcloud.entity.SpaceKnowledgeDocumentProfile;

import java.util.Objects;

public record KnowledgeSourceSnapshot(String sourceChunkIds,
                                      int sourceChunkCount,
                                      int sourceCharacterCount,
                                      String parserVersion,
                                      String signature) {
    public boolean matches(SpaceKnowledgeDocumentProfile profile) {
        return profile != null
                && Objects.equals(sourceChunkIds,profile.getSourceChunkIds())
                && sourceChunkCount == value(profile.getSourceChunkCount())
                && sourceCharacterCount == value(profile.getSourceCharacterCount())
                && Objects.equals(parserVersion,profile.getSourceParserVersion())
                && Objects.equals(signature,profile.getSourceSnapshotSignature());
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }
}
