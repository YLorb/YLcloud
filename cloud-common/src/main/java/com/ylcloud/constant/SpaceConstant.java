package com.ylcloud.constant;

/**
 * 空间类型与成员角色常量。
 */
public class SpaceConstant {
    /**
     * 默认个人空间。
     */
    public static final String TYPE_PERSONAL = "PERSONAL";

    /**
     * 普通团队空间。
     */
    public static final String TYPE_TEAM = "TEAM";

    /**
     * 空间所有者。
     */
    public static final String ROLE_OWNER = "OWNER";

    /**
     * 空间管理员。
     */
    public static final String ROLE_ADMIN = "ADMIN";

    /**
     * 空间普通成员。
     */
    public static final String ROLE_MEMBER = "MEMBER";

    /**
     * RAG 文档待索引。
     */
    public static final String RAG_INDEX_PENDING = "PENDING";

    /**
     * RAG 文档索引中。
     */
    public static final String RAG_INDEX_INDEXING = "INDEXING";

    /**
     * RAG 文档索引成功。
     */
    public static final String RAG_INDEX_SUCCESS = "SUCCESS";

    /** Persisted compatibility value for a RAG document whose index is query-ready. */
    public static final String RAG_INDEX_READY = RAG_INDEX_SUCCESS;

    /**
     * RAG 文档索引失败。
     */
    public static final String RAG_INDEX_FAILED = "FAILED";

    /**
     * RAG 任务待执行。
     */
    public static final String RAG_TASK_PENDING = "PENDING";

    /**
     * RAG 任务执行中。
     */
    public static final String RAG_TASK_RUNNING = "RUNNING";

    /**
     * RAG 任务执行成功。
     */
    public static final String RAG_TASK_SUCCESS = "SUCCESS";

    /**
     * RAG 任务执行失败。
     */
    public static final String RAG_TASK_FAILED = "FAILED";

    /**
     * 索引单个空间文件。
     */
    public static final String RAG_TASK_INDEX_FILE = "INDEX_FILE";

    /**
     * 重建单个空间文件索引。
     */
    public static final String RAG_TASK_REBUILD_FILE = "REBUILD_FILE";

    /**
     * 重建整个空间索引。
     */
    public static final String RAG_TASK_REBUILD_SPACE = "REBUILD_SPACE";

    /**
     * 删除单个空间文件索引。
     */
    public static final String RAG_TASK_DELETE_FILE = "DELETE_FILE";

    public static final String KNOWLEDGE_TASK_PROFILE_DOCUMENT = "PROFILE_DOCUMENT";

    public static final String KNOWLEDGE_TASK_PROFILE_SPACE = "PROFILE_SPACE";

    public static final String KNOWLEDGE_TASK_PENDING = "PENDING";

    public static final String KNOWLEDGE_TASK_RUNNING = "RUNNING";

    public static final String KNOWLEDGE_TASK_SUCCESS = "SUCCESS";

    public static final String KNOWLEDGE_TASK_PARTIAL_SUCCESS = "PARTIAL_SUCCESS";

    public static final String KNOWLEDGE_TASK_FAILED = "FAILED";

    public static final String KNOWLEDGE_TASK_SKIPPED = "SKIPPED";

    public static final String KNOWLEDGE_PROFILE_PENDING = "PENDING";

    public static final String KNOWLEDGE_PROFILE_GENERATED = "GENERATED";

    public static final String KNOWLEDGE_PROFILE_VALID = "VALID";

    public static final String KNOWLEDGE_PROFILE_SUCCESS = "SUCCESS";

    public static final String KNOWLEDGE_PROFILE_NEEDS_REVIEW = "NEEDS_REVIEW";

    public static final String KNOWLEDGE_PROFILE_FAILED = "FAILED";

    public static final String KNOWLEDGE_PROFILE_INVALID = "INVALID";

    public static final String KNOWLEDGE_PROFILE_SUPERSEDED = "SUPERSEDED";

    public static final String KNOWLEDGE_REVIEW_NOT_REQUIRED = "NOT_REQUIRED";

    public static final String KNOWLEDGE_REVIEW_PENDING = "PENDING_REVIEW";

    public static final String KNOWLEDGE_REVIEW_APPROVED = "APPROVED";

    public static final String KNOWLEDGE_REVIEW_REJECTED = "REJECTED";

    public static final String KNOWLEDGE_REVIEW_AUTO_FIXED = "AUTO_FIXED";

    public static final String KNOWLEDGE_STAGE_PENDING = "PENDING";

    public static final String KNOWLEDGE_STAGE_WAITING_RAG = "WAITING_RAG";

    public static final String KNOWLEDGE_STAGE_PROFILING = "PROFILING";

    public static final String KNOWLEDGE_STAGE_SAVING_PROFILE = "SAVING_PROFILE";

    public static final String KNOWLEDGE_STAGE_ENHANCING_RETRIEVAL = "ENHANCING_RETRIEVAL";

    public static final String KNOWLEDGE_STAGE_SUCCESS = "SUCCESS";

    public static final String KNOWLEDGE_STAGE_FAILED = "FAILED";

    public static final String KNOWLEDGE_STAGE_SKIPPED = "SKIPPED";

    public static final String KNOWLEDGE_PIPELINE_TASK_INITIALIZE = "TASK_INITIALIZE";

    public static final String KNOWLEDGE_PIPELINE_LOAD_CHUNKS = "LOAD_CHUNKS";

    public static final String KNOWLEDGE_PIPELINE_CHECK_INCREMENTAL = "CHECK_INCREMENTAL";

    public static final String KNOWLEDGE_PIPELINE_GENERATE_PROFILE = "GENERATE_PROFILE";

    public static final String KNOWLEDGE_PIPELINE_PARSE_PROFILE = "PARSE_PROFILE";

    public static final String KNOWLEDGE_PIPELINE_NORMALIZE_PROFILE = "NORMALIZE_PROFILE";

    public static final String KNOWLEDGE_PIPELINE_VALIDATE_PROFILE = "VALIDATE_PROFILE";

    public static final String KNOWLEDGE_PIPELINE_SCORE_PROFILE = "SCORE_PROFILE";

    public static final String KNOWLEDGE_PIPELINE_REPAIR_PROFILE = "REPAIR_PROFILE";

    public static final String KNOWLEDGE_PIPELINE_SAVE_PROFILE = "SAVE_PROFILE";

    public static final String KNOWLEDGE_PIPELINE_SYNC_RETRIEVAL_SOURCE = "SYNC_RETRIEVAL_SOURCE";

    /**
     * Legacy persisted stage value. New tasks use {@link #KNOWLEDGE_PIPELINE_SYNC_RETRIEVAL_SOURCE}.
     */
    @Deprecated
    public static final String KNOWLEDGE_PIPELINE_BUILD_RETRIEVAL_ENHANCEMENT = "BUILD_RETRIEVAL_ENHANCEMENT";

    public static final String KNOWLEDGE_PIPELINE_COMPLETE = "COMPLETE";

    public static final String KNOWLEDGE_EVENT_TASK_CREATED = "TASK_CREATED";

    public static final String KNOWLEDGE_EVENT_STAGE_STARTED = "STAGE_STARTED";

    public static final String KNOWLEDGE_EVENT_STAGE_FINISHED = "STAGE_FINISHED";

    public static final String KNOWLEDGE_EVENT_STAGE_FAILED = "STAGE_FAILED";

    public static final String KNOWLEDGE_EVENT_REVIEW_REQUIRED = "REVIEW_REQUIRED";

    public static final String KNOWLEDGE_EVENT_TASK_FINISHED = "TASK_FINISHED";

    public static final String KNOWLEDGE_EVENT_STATUS_RUNNING = "RUNNING";

    public static final String KNOWLEDGE_EVENT_STATUS_SUCCEEDED = "SUCCEEDED";

    public static final String KNOWLEDGE_EVENT_STATUS_FAILED = "FAILED";

    public static final String KNOWLEDGE_INCREMENTAL_FORCE_REBUILD = "FORCE_REBUILD";

    public static final String KNOWLEDGE_INCREMENTAL_REBUILD_PROFILE = "REBUILD_PROFILE";

    public static final String KNOWLEDGE_INCREMENTAL_SYNC_RETRIEVAL_SOURCE = "SYNC_RETRIEVAL_SOURCE";

    /**
     * Legacy persisted action value. It never represented a separate enhancement index.
     */
    @Deprecated
    public static final String KNOWLEDGE_INCREMENTAL_REBUILD_RETRIEVAL_ONLY = "REBUILD_RETRIEVAL_ONLY";

    public static final String KNOWLEDGE_INCREMENTAL_SKIP_PROFILE = "SKIP_PROFILE";

    public static final String KNOWLEDGE_TERMINAL_UNCHANGED_DOCUMENT = "UNCHANGED_DOCUMENT";

    public static final String KNOWLEDGE_TERMINAL_SOURCE_SNAPSHOT_SYNCED = "SOURCE_SNAPSHOT_SYNCED";

    public static final String KNOWLEDGE_TERMINAL_PROFILE_DISABLED = "PROFILE_SKIPPED_DISABLED";

    public static final String KNOWLEDGE_TERMINAL_NO_ELIGIBLE_DOCUMENTS = "NO_ELIGIBLE_DOCUMENTS";

    @Deprecated
    public static final String KNOWLEDGE_TERMINAL_RETRIEVAL_ONLY = "RETRIEVAL_ONLY";
}
