package com.ylcloud.mapper;

import com.ylcloud.entity.DataExportJob;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface DataExportJobMapper {

    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("insert into data_export_job(user_id, job_key, status, export_scope, encryption_key_id, " +
            "encrypted_key, encrypted_iv, async_task_id, created_at, updated_at) " +
            "values(#{userId}, #{jobKey}, #{status}, #{exportScope}, #{encryptionKeyId}, " +
            "#{encryptedKey}, #{encryptedIv}, #{asyncTaskId}, #{createdAt}, #{updatedAt})")
    int insert(DataExportJob job);

    @Select("select id, user_id as userId, job_key as jobKey, status, export_scope as exportScope, " +
            "encryption_key_id as encryptionKeyId, encrypted_key as encryptedKey, encrypted_iv as encryptedIv, " +
            "storage_path as storagePath, file_size_bytes as fileSizeBytes, " +
            "file_hash as fileHash, download_url as downloadUrl, download_expires_at as downloadExpiresAt, " +
            "async_task_id as asyncTaskId, started_at as startedAt, finished_at as finishedAt, " +
            "last_error as lastError, created_at as createdAt, updated_at as updatedAt " +
            "from data_export_job where id = #{id}")
    DataExportJob getById(@Param("id") Long id);

    @Select("select id, user_id as userId, job_key as jobKey, status, export_scope as exportScope, " +
            "encryption_key_id as encryptionKeyId, encrypted_key as encryptedKey, encrypted_iv as encryptedIv, " +
            "storage_path as storagePath, file_size_bytes as fileSizeBytes, " +
            "file_hash as fileHash, download_url as downloadUrl, download_expires_at as downloadExpiresAt, " +
            "async_task_id as asyncTaskId, started_at as startedAt, finished_at as finishedAt, " +
            "last_error as lastError, created_at as createdAt, updated_at as updatedAt " +
            "from data_export_job where user_id = #{userId} order by created_at desc limit #{limit}")
    List<DataExportJob> listByUserId(@Param("userId") Long userId, @Param("limit") int limit);

    @Select("select id from data_export_job where user_id = #{userId} and status in ('PENDING','RUNNING') for update")
    Long lockActiveByUserId(@Param("userId") Long userId);

    @Update("update data_export_job set status = #{status}, encryption_key_id = #{encryptionKeyId}, " +
            "encrypted_key = #{encryptedKey}, encrypted_iv = #{encryptedIv}, " +
            "storage_path = #{storagePath}, " +
            "file_size_bytes = #{fileSizeBytes}, file_hash = #{fileHash}, download_url = #{downloadUrl}, " +
            "download_expires_at = #{downloadExpiresAt}, async_task_id = #{asyncTaskId}, " +
            "started_at = #{startedAt}, finished_at = #{finishedAt}, " +
            "last_error = #{lastError}, updated_at = #{updatedAt} where id = #{id}")
    int update(DataExportJob job);

    @Update("update data_export_job set status = 'RUNNING', started_at = #{now}, updated_at = #{now} " +
            "where id = #{id} and status = 'PENDING'")
    int markRunning(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Update("update data_export_job set status = 'COMPLETED', storage_path = #{storagePath}, " +
            "file_size_bytes = #{fileSizeBytes}, file_hash = #{fileHash}, download_url = #{downloadUrl}, " +
            "download_expires_at = #{downloadExpiresAt}, finished_at = #{now}, updated_at = #{now} where id = #{id}")
    int markCompleted(@Param("id") Long id, @Param("storagePath") String storagePath,
                      @Param("fileSizeBytes") Long fileSizeBytes, @Param("fileHash") String fileHash,
                      @Param("downloadUrl") String downloadUrl, @Param("downloadExpiresAt") LocalDateTime downloadExpiresAt,
                      @Param("now") LocalDateTime now);

    @Update("update data_export_job set status = 'FAILED', last_error = #{error}, finished_at = #{now}, updated_at = #{now} " +
            "where id = #{id}")
    int markFailed(@Param("id") Long id, @Param("error") String error, @Param("now") LocalDateTime now);

    @Update("update data_export_job set status = 'EXPIRED', updated_at = #{now} " +
            "where id = #{id} and status = 'COMPLETED' and download_expires_at < #{now}")
    int markExpired(@Param("id") Long id, @Param("now") LocalDateTime now);
}
