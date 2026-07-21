package com.ylcloud.workflow.security;

/**
 * 服务 Token 的资源绑定。调用方只填写当前调用可知的字段；例如创建 Run 时尚无 runId，
 * 但必须绑定 user/session/assistantMessage。
 */
public record ServiceJwtBinding(
        Long userId,
        Long sessionId,
        Long messageId,
        String runId,
        String executionId,
        String nodeId,
        String invocationId
) {
    public static ServiceJwtBinding runCreate(long userId, long sessionId, long messageId) {
        return new ServiceJwtBinding(userId, sessionId, messageId, null, null, null, null);
    }

    public static ServiceJwtBinding run(String runId) {
        return new ServiceJwtBinding(null, null, null, runId, null, null, null);
    }
}
