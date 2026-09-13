package com.ylcloud.workflow.client;

/** 内部错误分类不包含响应正文、Token、用户问题或连接 Secret。 */
public class WorkflowClientException extends RuntimeException {
    private final String code;
    private final boolean retryable;
    private final int httpStatus;

    public WorkflowClientException(String code, String message, boolean retryable, int httpStatus) {
        super(message);
        this.code = code;
        this.retryable = retryable;
        this.httpStatus = httpStatus;
    }

    public String getCode() { return code; }
    public boolean isRetryable() { return retryable; }
    public int getHttpStatus() { return httpStatus; }
}
