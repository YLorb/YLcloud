package com.ylcloud.constant;

/**
 * 分片上传任务状态常量。
 */
public class UploadTaskConstant {
    /**
     * 上传中。
     */
    public static final Integer UPLOADING = 1;

    /**
     * 已完成分片合并。
     */
    public static final Integer MERGED = 2;

    /**
     * 已取消。
     */
    public static final Integer CANCEL = 3;

    /**
     * 上传或合并失败。
     */
    public static final Integer FAIL = 4;
}
