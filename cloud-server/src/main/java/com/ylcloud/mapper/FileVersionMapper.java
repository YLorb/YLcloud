package com.ylcloud.mapper;

import com.ylcloud.entity.FileVersion;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 文件历史版本 Mapper。
 */
@Mapper
public interface FileVersionMapper {

    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("insert into file_version(file_uuid, version_no, minio_version_id, file_name, file_hash, file_md5, file_type, file_size, change_note, created_by, is_current, status, createtime) " +
            "values(#{fileUuid}, #{versionNo}, #{minioVersionId}, #{fileName}, #{fileHash}, #{fileMd5}, #{fileType}, #{fileSize}, #{changeNote}, #{createdBy}, #{current}, #{status}, #{createtime})")
    int insert(FileVersion version);

    @Select("select id, file_uuid as fileUuid, version_no as versionNo, minio_version_id as minioVersionId, file_name as fileName, " +
            "file_hash as fileHash, file_md5 as fileMd5, file_type as fileType, file_size as fileSize, change_note as changeNote, " +
            "created_by as createdBy, is_current as current, status, createtime from file_version " +
            "where file_uuid = #{fileUuid} and status = 1 order by version_no desc")
    List<FileVersion> listByFileUuid(@Param("fileUuid") String fileUuid);

    @Select("select id, file_uuid as fileUuid, version_no as versionNo, minio_version_id as minioVersionId, file_name as fileName, " +
            "file_hash as fileHash, file_md5 as fileMd5, file_type as fileType, file_size as fileSize, change_note as changeNote, " +
            "created_by as createdBy, is_current as current, status, createtime from file_version " +
            "where id = #{id} and file_uuid = #{fileUuid} and status = 1")
    FileVersion getByIdAndFileUuid(@Param("id") Long id, @Param("fileUuid") String fileUuid);

    @Select("select coalesce(max(version_no),0) from file_version where file_uuid = #{fileUuid} and status = 1")
    int getMaxVersionNo(@Param("fileUuid") String fileUuid);

    @Update("update file_version set is_current = 0 where file_uuid = #{fileUuid} and status = 1")
    int clearCurrent(@Param("fileUuid") String fileUuid);
}
