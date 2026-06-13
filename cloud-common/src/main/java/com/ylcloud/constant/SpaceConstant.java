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
}
