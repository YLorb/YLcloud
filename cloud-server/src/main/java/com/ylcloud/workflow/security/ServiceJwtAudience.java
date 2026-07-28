package com.ylcloud.workflow.security;

/** 服务间 JWT 的独立 audience；不同方向的 Token 不允许复用。 */
public final class ServiceJwtAudience {
    public static final String WORKFLOW = "ylcloud-workflow";
    public static final String TOOL_GATEWAY = "ylcloud-tool-gateway";
    public static final String MODEL_SERVICE = "ylcloud-model-service";
    public static final String WORKFLOW_CALLBACK = "ylcloud-workflow-callback";

    private ServiceJwtAudience() {
    }
}
