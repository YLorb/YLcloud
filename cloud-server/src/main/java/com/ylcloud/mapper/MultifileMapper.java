package com.ylcloud.mapper;

import com.ylcloud.entity.UploadTask;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 分片上传任务表 Mapper。
 */
@Mapper
public interface MultifileMapper {

    /**
     * 查询 getByUploadId 相关逻辑。
     * @return 处理结果
     */
    @Select("select id, upload_id as uploadId, file_key as fileKey, user_id as userId, parent_id as parentId, " +
            "file_name as fileName, file_size as fileSize, file_md5 as fileMd5, file_sha1 as fileSha1, file_hash as fileHash, " +
            "chunk_size as chunkSize, total_chunks as totalChunks, uploaded_chunks as uploadedChunks, " +
            "status, file_uuid as fileUuid, last_activity_time as lastActivityTime, merge_started_time as mergeStartedTime, " +
            "parts_cleaned_time as partsCleanedTime, cleanup_attempt_count as cleanupAttemptCount, createtime, updatetime " +
            "from upload_task " +
            "where upload_id = #{uploadId} " +
            "and user_id = #{userId} " +
            "limit 1")
    UploadTask getByUploadId(@Param("uploadId") String uploadId, @Param("userId") Long userId);

    /**
     * 查询 getActiveTask 相关逻辑。
     * @return 处理结果
     */
    @Select("select id, upload_id as uploadId, file_key as fileKey, user_id as userId, parent_id as parentId, " +
            "file_name as fileName, file_size as fileSize, file_md5 as fileMd5, file_sha1 as fileSha1, file_hash as fileHash, " +
            "chunk_size as chunkSize, total_chunks as totalChunks, uploaded_chunks as uploadedChunks, " +
            "status, file_uuid as fileUuid, last_activity_time as lastActivityTime, merge_started_time as mergeStartedTime, " +
            "parts_cleaned_time as partsCleanedTime, cleanup_attempt_count as cleanupAttemptCount, createtime, updatetime " +
            "from upload_task " +
            "where user_id = #{userId} " +
            "and parent_id = #{parentId} " +
            "and file_hash = #{fileHash} " +
            "and file_name = #{fileName} " +
            "and status = 1 " +
            "order by updatetime desc " +
            "limit 1")
    UploadTask getActiveTask(@Param("userId") Long userId,
                             @Param("parentId") Long parentId,
                             @Param("fileHash") String fileHash,
                             @Param("fileName") String fileName);

    @Select("select id, upload_id as uploadId, file_key as fileKey, user_id as userId, parent_id as parentId, " +
            "file_name as fileName, file_size as fileSize, file_md5 as fileMd5, file_sha1 as fileSha1, file_hash as fileHash, " +
            "chunk_size as chunkSize, total_chunks as totalChunks, uploaded_chunks as uploadedChunks, status, " +
            "file_uuid as fileUuid, last_activity_time as lastActivityTime, merge_started_time as mergeStartedTime, " +
            "parts_cleaned_time as partsCleanedTime, cleanup_attempt_count as cleanupAttemptCount, createtime, updatetime " +
            "from upload_task where file_key = #{fileKey} and status in (1, 4, 5) limit 1")
    UploadTask getActiveByFileKey(@Param("fileKey") String fileKey);

    /**
     * 新增 insert 相关逻辑。
     * @return 影响行数
     */
    @Insert("insert into upload_task(upload_id, file_key, user_id, parent_id, file_name, file_size, file_md5, file_sha1, file_hash, " +
            "chunk_size, total_chunks, uploaded_chunks, status, file_uuid, last_activity_time, merge_started_time, createtime, updatetime) " +
            "values(#{uploadId}, #{fileKey}, #{userId}, #{parentId}, #{fileName}, #{fileSize}, #{fileMd5}, #{fileSha1}, " +
            "#{fileHash}, #{chunkSize}, #{totalChunks}, #{uploadedChunks}, #{status}, #{fileUuid}, #{lastActivityTime}, " +
            "#{mergeStartedTime}, #{createtime}, #{updatetime})")
    int insert(UploadTask uploadTask);

    /**
     * 执行 increaseUploadedChunks 函数的业务处理。
     * @return 影响行数
     */
    @Update("update upload_task " +
            "set uploaded_chunks = uploaded_chunks + 1, last_activity_time = now(), updatetime = now() " +
            "where upload_id = #{uploadId} " +
            "and status = 1")
    int increaseUploadedChunks(@Param("uploadId") String uploadId);

    @Update("update upload_task set last_activity_time = now(), updatetime = now() " +
            "where upload_id = #{uploadId} and status = 1")
    int touch(@Param("uploadId") String uploadId);

    @Update("update upload_task set status = 5, merge_started_time = now(), last_activity_time = now(), updatetime = now() " +
            "where upload_id = #{uploadId} and user_id = #{userId} and status in (1, 4)")
    int claimMerge(@Param("uploadId") String uploadId, @Param("userId") Long userId);

    /**
     * 标记 markMerged 相关逻辑。
     * @return 影响行数
     */
    @Update("update upload_task " +
            "set status = 2, file_uuid = #{fileUuid}, updatetime = now() " +
            "where upload_id = #{uploadId} and status = 5")
    int markMerged(@Param("uploadId") String uploadId, @Param("fileUuid") String fileUuid);

    /**
     * 标记 markFail 相关逻辑。
     * @return 影响行数
     */
    @Update("update upload_task " +
            "set status = 4, updatetime = now() " +
            "where upload_id = #{uploadId} and status = 5")
    int markFail(@Param("uploadId") String uploadId);

    @Select("select id, upload_id as uploadId, file_key as fileKey, user_id as userId, parent_id as parentId, " +
            "file_name as fileName, file_size as fileSize, file_md5 as fileMd5, file_sha1 as fileSha1, file_hash as fileHash, " +
            "chunk_size as chunkSize, total_chunks as totalChunks, uploaded_chunks as uploadedChunks, status, " +
            "file_uuid as fileUuid, last_activity_time as lastActivityTime, merge_started_time as mergeStartedTime, " +
            "parts_cleaned_time as partsCleanedTime, cleanup_attempt_count as cleanupAttemptCount, createtime, updatetime " +
            "from upload_task where status in (1, 4) and last_activity_time < #{cutoff} order by last_activity_time limit #{limit}")
    java.util.List<UploadTask> listStale(@Param("cutoff") java.time.LocalDateTime cutoff, @Param("limit") Integer limit);

    @Update("update upload_task set status = 6, updatetime = now() where id = #{id} and status in (1, 4) and last_activity_time < #{cutoff}")
    int markExpired(@Param("id") Long id, @Param("cutoff") java.time.LocalDateTime cutoff);

    @Update("update upload_task set status = 4, updatetime = now() where id = #{id} and status = 6")
    int markCleanupRetry(@Param("id") Long id);

    @Update("update upload_task set status = 4, merge_started_time = null, updatetime = now() " +
            "where status = 5 and merge_started_time < #{cutoff}")
    int recoverStaleMerges(@Param("cutoff") java.time.LocalDateTime cutoff);

    @Select("select id, upload_id as uploadId, file_uuid as fileUuid from upload_task " +
            "where status = 2 and parts_cleaned_time is null order by updatetime asc limit #{limit}")
    java.util.List<UploadTask> listMergedPendingCleanup(@Param("limit") Integer limit);

    @Update("update upload_task set parts_cleaned_time = #{now}, cleanup_attempt_count = cleanup_attempt_count + 1, updatetime = now() " +
            "where id = #{id} and status = 2 and parts_cleaned_time is null")
    int markPartsCleaned(@Param("id") Long id, @Param("now") java.time.LocalDateTime now);

    @Update("update upload_task set cleanup_attempt_count = cleanup_attempt_count + 1, updatetime = now() " +
            "where id = #{id} and status = 2 and parts_cleaned_time is null")
    int markPartsCleanupFailed(@Param("id") Long id);
}
