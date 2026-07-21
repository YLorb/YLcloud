package com.ylcloud.workflow.tool;

import java.util.UUID;

/** 已通过服务身份校验的 Tool 调用上下文，handler 不接触原始 Token。 */
public record ToolInvocationContext(UUID runId, UUID executionId, String nodeId, UUID invocationId,
                                    long userId, long sessionId) {
    public boolean userIdEquals(Long value) { return value != null && value == userId; }
}
