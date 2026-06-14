package com.ylcloud.service.rag;

import lombok.Data;

@Data
public class RagChatResult {
    private String answer;
    private boolean success;
    private String errorMessage;

    public static RagChatResult success(String answer) {
        RagChatResult result = new RagChatResult();
        result.setAnswer(answer);
        result.setSuccess(true);
        return result;
    }

    public static RagChatResult failed(String answer, String errorMessage) {
        RagChatResult result = new RagChatResult();
        result.setAnswer(answer);
        result.setSuccess(false);
        result.setErrorMessage(errorMessage);
        return result;
    }
}
