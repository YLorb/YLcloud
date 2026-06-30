package com.ylcloud.service.knowledge.pipeline;

public record KnowledgePipelineIncrementalDecision(String action,
                                                   String terminalStage,
                                                   String terminalReason,
                                                   String detail) {
    public boolean skipsProfile() {
        return "SKIP_PROFILE".equals(action);
    }

    public boolean rebuildsRetrievalOnly() {
        return "REBUILD_RETRIEVAL_ONLY".equals(action);
    }
}
