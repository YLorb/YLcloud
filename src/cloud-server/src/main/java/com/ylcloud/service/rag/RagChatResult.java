package com.ylcloud.service.rag;

import lombok.Data;

@Data
public class RagChatResult {
    private String answer;
    private boolean success;
    private boolean noAnswer;
    private String errorMessage;
    private String modelName;

    /**
     * 执行 success 函数的业务处理。
     *
     * @param answer 方法入参
     * @return 处理结果
     */
    public static RagChatResult success(String answer) {
        RagChatResult result = new RagChatResult();
        result.setAnswer(answer);
        result.setSuccess(true);
        return result;
    }

    /**
     * 执行 failed 函数的业务处理。
     *
     * @param answer 方法入参
     * @param errorMessage 错误信息
     * @return 处理结果
     */
    public static RagChatResult failed(String answer, String errorMessage) {
        RagChatResult result = new RagChatResult();
        result.setAnswer(answer);
        result.setSuccess(false);
        result.setErrorMessage(errorMessage);
        return result;
    }

    public static RagChatResult noAnswer(String answer) {
        RagChatResult result = success(answer);
        result.setNoAnswer(true);
        return result;
    }

    public static RagChatResult noAnswer(String answer, String errorMessage) {
        RagChatResult result = failed(answer,errorMessage);
        result.setNoAnswer(true);
        return result;
    }
}
