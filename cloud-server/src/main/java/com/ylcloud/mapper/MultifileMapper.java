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
     * 查询 getActiveTask 相关逻辑。
     * @return 处理结果
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
     * 新增 insert 相关逻辑。
     * @return 影响行数
     */
    @Insert("insert into upload_task(upload_id, user_id, parent_id, file_name, file_size, file_md5, file_hash, " +
            "chunk_size, total_chunks, uploaded_chunks, status, file_uuid, createtime, updatetime) " +
            "values(#{uploadId}, #{userId}, #{parentId}, #{fileName}, #{fileSize}, #{fileMd5}, #{fileHash}, " +
            "#{chunkSize}, #{totalChunks}, #{uploadedChunks}, #{status}, #{fileUuid}, #{createtime}, #{updatetime})")
    int insert(UploadTask uploadTask);

    /**
     * 执行 increaseUploadedChunks 函数的业务处理。
     * @return 影响行数
     */
    @Update("update upload_task " +
            "set uploaded_chunks = uploaded_chunks + 1, updatetime = now() " +
            "where upload_id = #{uploadId} " +
            "and status = 1")
    int increaseUploadedChunks(@Param("uploadId") String uploadId);

    /**
     * 标记 markMerged 相关逻辑。
     * @return 影响行数
     */
    @Update("update upload_task " +
            "set status = 2, file_uuid = #{fileUuid}, updatetime = now() " +
            "where upload_id = #{uploadId}")
    int markMerged(@Param("uploadId") String uploadId, @Param("fileUuid") String fileUuid);

    /**
     * 标记 markFail 相关逻辑。
     * @return 影响行数
     */
    @Update("update upload_task " +
            "set status = 4, updatetime = now() " +
            "where upload_id = #{uploadId}")
    int markFail(@Param("uploadId") String uploadId);
}
