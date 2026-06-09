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
     * 根据上传任务 ID 和用户 ID 查询上传任务。
     *
     * @param uploadId 上传任务 ID
     * @param userId 用户 ID
     * @return 上传任务信息，不存在时返回 null
     */
    @Select("select id, upload_id as uploadId, user_id as userId, parent_id as parentId, " +
            "file_name as fileName, file_size as fileSize, file_md5 as fileMd5, file_hash as fileHash, " +
            "chunk_size as chunkSize, total_chunks as totalChunks, uploaded_chunks as uploadedChunks, " +
            "status, file_uuid as fileUuid, createtime, updatetime " +
            "from upload_task " +
            "where upload_id = #{uploadId} " +
            "and user_id = #{userId} " +
            "limit 1")
    UploadTask getByUploadId(@Param("uploadId") String uploadId, @Param("userId") Long userId);

    /**
     * 查询同一用户、同一目录、同一文件 hash 的未完成上传任务。
     *
     * @param userId 用户 ID
     * @param parentId 父目录 ID
     * @param fileHash 完整文件 hash
     * @param fileName 原始文件名
     * @return 未完成上传任务，不存在时返回 null
     */
    @Select("select id, upload_id as uploadId, user_id as userId, parent_id as parentId, " +
            "file_name as fileName, file_size as fileSize, file_md5 as fileMd5, file_hash as fileHash, " +
            "chunk_size as chunkSize, total_chunks as totalChunks, uploaded_chunks as uploadedChunks, " +
            "status, file_uuid as fileUuid, createtime, updatetime " +
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

    /**
     * 新增上传任务。
     *
     * @param uploadTask 上传任务实体
     * @return 影响行数
     */
    @Insert("insert into upload_task(upload_id, user_id, parent_id, file_name, file_size, file_md5, file_hash, " +
            "chunk_size, total_chunks, uploaded_chunks, status, file_uuid, createtime, updatetime) " +
            "values(#{uploadId}, #{userId}, #{parentId}, #{fileName}, #{fileSize}, #{fileMd5}, #{fileHash}, " +
            "#{chunkSize}, #{totalChunks}, #{uploadedChunks}, #{status}, #{fileUuid}, #{createtime}, #{updatetime})")
    int insert(UploadTask uploadTask);

    /**
     * 上传成功一个分片后递增已上传分片数量。
     *
     * @param uploadId 上传任务 ID
     * @return 影响行数
     */
    @Update("update upload_task " +
            "set uploaded_chunks = uploaded_chunks + 1, updatetime = now() " +
            "where upload_id = #{uploadId} " +
            "and status = 1")
    int increaseUploadedChunks(@Param("uploadId") String uploadId);

    /**
     * 标记上传任务已合并完成。
     *
     * @param uploadId 上传任务 ID
     * @param fileUuid 合并后最终文件 UUID
     * @return 影响行数
     */
    @Update("update upload_task " +
            "set status = 2, file_uuid = #{fileUuid}, updatetime = now() " +
            "where upload_id = #{uploadId}")
    int markMerged(@Param("uploadId") String uploadId, @Param("fileUuid") String fileUuid);

    /**
     * 标记上传任务失败。
     *
     * @param uploadId 上传任务 ID
     * @return 影响行数
     */
    @Update("update upload_task " +
            "set status = 4, updatetime = now() " +
            "where upload_id = #{uploadId}")
    int markFail(@Param("uploadId") String uploadId);
}
