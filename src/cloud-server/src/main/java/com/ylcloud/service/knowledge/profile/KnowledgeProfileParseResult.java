package com.ylcloud.service.knowledge.profile;

import lombok.Data;

@Data
public class KnowledgeProfileParseResult {
    private KnowledgeProfileDraft profile;
    private boolean parsed;
    private String errorCode;
    private String errorMessage;

    public static KnowledgeProfileParseResult success(KnowledgeProfileDraft profile) {
        KnowledgeProfileParseResult result = new KnowledgeProfileParseResult();
        result.setProfile(profile);
        result.setParsed(true);
        return result;
    }

    public static KnowledgeProfileParseResult failed(String errorCode, String errorMessage) {
        KnowledgeProfileParseResult result = new KnowledgeProfileParseResult();
        result.setProfile(new KnowledgeProfileDraft());
        result.setParsed(false);
        result.setErrorCode(errorCode);
        result.setErrorMessage(errorMessage);
        return result;
    }
}
