package com.ylcloud.mapper;

import com.ylcloud.entity.BackupRun;
import com.ylcloud.entity.RestoreVerification;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface BackupMapper {
    String BACKUP_COLUMNS = "id, run_key as runKey, backup_type as backupType, status, " +
            "manifest_json as manifestJson, mysql_dump_path as mysqlDumpPath, " +
            "minio_snapshot_path as minioSnapshotPath, qdrant_snapshot_path as qdrantSnapshotPath, " +
            "config_snapshot_path as configSnapshotPath, archive_path as archivePath, " +
            "archive_size_bytes as archiveSizeBytes, archive_hash as archiveHash, " +
            "encryption_key_id as encryptionKeyId, started_at as startedAt, finished_at as finishedAt, " +
            "verified_at as verifiedAt, published_at as publishedAt, last_error as lastError, " +
            "created_at as createdAt, updated_at as updatedAt";

    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("insert into backup_run(run_key, backup_type, status, manifest_json, mysql_dump_path, " +
            "minio_snapshot_path, qdrant_snapshot_path, config_snapshot_path, archive_path, " +
            "archive_size_bytes, archive_hash, encryption_key_id, started_at, finished_at, " +
            "verified_at, published_at, last_error, created_at, updated_at) " +
            "values(#{runKey}, #{backupType}, #{status}, #{manifestJson}, #{mysqlDumpPath}, " +
            "#{minioSnapshotPath}, #{qdrantSnapshotPath}, #{configSnapshotPath}, #{archivePath}, " +
            "#{archiveSizeBytes}, #{archiveHash}, #{encryptionKeyId}, #{startedAt}, #{finishedAt}, " +
            "#{verifiedAt}, #{publishedAt}, #{lastError}, #{createdAt}, #{updatedAt})")
    int insertBackup(BackupRun backup);

    @Select("select " + BACKUP_COLUMNS + " from backup_run where id = #{id}")
    BackupRun getBackupById(@Param("id") Long id);

    @Select("select " + BACKUP_COLUMNS + " from backup_run where run_key = #{runKey}")
    BackupRun getBackupByKey(@Param("runKey") String runKey);

    @Select("select " + BACKUP_COLUMNS + " from backup_run where status = 'READY' " +
            "order by published_at desc limit #{limit}")
    List<BackupRun> listReadyBackups(@Param("limit") int limit);

    @Select("select " + BACKUP_COLUMNS + " from backup_run " +
            "order by created_at desc limit #{limit}")
    List<BackupRun> listRecentBackups(@Param("limit") int limit);

    @Update("update backup_run set status = #{status}, manifest_json = #{manifestJson}, " +
            "archive_path = #{archivePath}, archive_size_bytes = #{archiveSizeBytes}, " +
            "archive_hash = #{archiveHash}, started_at = #{startedAt}, finished_at = #{finishedAt}, " +
            "verified_at = #{verifiedAt}, published_at = #{publishedAt}, last_error = #{lastError}, " +
            "updated_at = #{updatedAt} where id = #{id}")
    int updateBackup(BackupRun backup);

    @Update("update backup_run set status = 'READY', verified_at = #{verifiedAt}, " +
            "published_at = #{publishedAt}, updated_at = #{updatedAt} where id = #{id}")
    int markReady(@Param("id") Long id, @Param("verifiedAt") LocalDateTime verifiedAt,
                  @Param("publishedAt") LocalDateTime publishedAt, @Param("updatedAt") LocalDateTime updatedAt);

    @Update("update backup_run set status = 'FAILED', last_error = #{error}, " +
            "finished_at = #{finishedAt}, updated_at = #{updatedAt} where id = #{id}")
    int markFailed(@Param("id") Long id, @Param("error") String error,
                   @Param("finishedAt") LocalDateTime finishedAt, @Param("updatedAt") LocalDateTime updatedAt);

    @Update("update backup_run set status = 'EXPIRED', updated_at = #{updatedAt} " +
            "where id = #{id} and status = 'READY'")
    int markExpired(@Param("id") Long id, @Param("updatedAt") LocalDateTime updatedAt);

    @Select("select count(1) from backup_run where status = 'READY'")
    int countReadyBackups();

    // Restore verification
    String RESTORE_COLUMNS = "id, backup_run_id as backupRunId, verification_key as verificationKey, " +
            "status, restore_environment as restoreEnvironment, mysql_restored as mysqlRestored, " +
            "minio_restored as minioRestored, qdrant_restored as qdrantRestored, " +
            "config_restored as configRestored, business_sample_check as businessSampleCheck, " +
            "started_at as startedAt, finished_at as finishedAt, last_error as lastError, " +
            "created_at as createdAt, updated_at as updatedAt";

    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("insert into restore_verification(backup_run_id, verification_key, status, restore_environment, " +
            "mysql_restored, minio_restored, qdrant_restored, config_restored, business_sample_check, " +
            "started_at, finished_at, last_error, created_at, updated_at) " +
            "values(#{backupRunId}, #{verificationKey}, #{status}, #{restoreEnvironment}, " +
            "#{mysqlRestored}, #{minioRestored}, #{qdrantRestored}, #{configRestored}, #{businessSampleCheck}, " +
            "#{startedAt}, #{finishedAt}, #{lastError}, #{createdAt}, #{updatedAt})")
    int insertVerification(RestoreVerification verification);

    @Select("select " + RESTORE_COLUMNS + " from restore_verification where id = #{id}")
    RestoreVerification getVerificationById(@Param("id") Long id);

    @Select("select " + RESTORE_COLUMNS + " from restore_verification where backup_run_id = #{backupRunId} " +
            "order by created_at desc limit #{limit}")
    List<RestoreVerification> listVerificationsByBackup(@Param("backupRunId") Long backupRunId, @Param("limit") int limit);

    @Update("update restore_verification set status = #{status}, mysql_restored = #{mysqlRestored}, " +
            "minio_restored = #{minioRestored}, qdrant_restored = #{qdrantRestored}, " +
            "config_restored = #{configRestored}, business_sample_check = #{businessSampleCheck}, " +
            "started_at = #{startedAt}, finished_at = #{finishedAt}, last_error = #{lastError}, " +
            "updated_at = #{updatedAt} where id = #{id}")
    int updateVerification(RestoreVerification verification);
}
