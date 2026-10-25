package com.ylcloud.service.rag;

import lombok.Data;
import com.ylcloud.VO.RagAnswerClaim;
import com.ylcloud.VO.RagAnswerEvidence;
import java.util.List;

@Data
public class RagChatResult {
    private String answer;
    private boolean success;
    private boolean noAnswer;
    private String errorMessage;
    private String modelName;
    private String status;
    private String reasonCode;
    private String questionType;
    private boolean verified;
    private List<RagAnswerClaim> claims = List.of();
    private List<RagAnswerEvidence> evidence = List.of();
    private List<Long> supportedChunkIds = List.of();

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
        result.setStatus("ANSWERED");
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
        result.setStatus("UNAVAILABLE");
        return result;
    }

    public static RagChatResult noAnswer(String answer) {
        RagChatResult result = success(answer);
        result.setNoAnswer(true);
        result.setStatus("REFUSED");
        return result;
    }

    public static RagChatResult noAnswer(String answer, String errorMessage) {
        RagChatResult result = failed(answer,errorMessage);
        result.setNoAnswer(true);
        return result;
    }
}
