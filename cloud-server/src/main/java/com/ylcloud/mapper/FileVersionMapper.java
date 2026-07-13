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

    @Update("update file_version set status = 0, is_current = 0 where file_uuid = #{fileUuid} and status = 1")
    int disableByFileUuid(@Param("fileUuid") String fileUuid);

    /**
     * 新增 insert 相关逻辑。
     * @return 影响行数
     */
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("insert into file_version(file_uuid, version_no, minio_version_id, file_name, file_hash, file_md5, file_type, file_size, change_note, created_by, is_current, status, createtime) " +
            "values(#{fileUuid}, #{versionNo}, #{minioVersionId}, #{fileName}, #{fileHash}, #{fileMd5}, #{fileType}, #{fileSize}, #{changeNote}, #{createdBy}, #{current}, #{status}, #{createtime})")
    int insert(FileVersion version);

    /**
     * 幂等写入物理文件的初始版本。并发导入同一物理文件时只保留一条 v1。
     */
    @Insert("insert ignore into file_version(file_uuid, version_no, minio_version_id, file_name, file_hash, file_md5, file_type, file_size, change_note, created_by, is_current, status, createtime) " +
            "values(#{fileUuid}, 1, #{minioVersionId}, #{fileName}, #{fileHash}, #{fileMd5}, #{fileType}, #{fileSize}, #{changeNote}, #{createdBy}, 1, 1, #{createtime})")
    int insertInitial(FileVersion version);

    /**
     * 查询 listByFileUuid 相关逻辑。
     * @return 列表结果
     */
    @Select("select id, file_uuid as fileUuid, version_no as versionNo, minio_version_id as minioVersionId, file_name as fileName, " +
            "file_hash as fileHash, file_md5 as fileMd5, file_type as fileType, file_size as fileSize, change_note as changeNote, " +
            "created_by as createdBy, is_current as current, status, createtime from file_version " +
            "where file_uuid = #{fileUuid} and status = 1 order by version_no desc")
    List<FileVersion> listByFileUuid(@Param("fileUuid") String fileUuid);

    /**
     * 查询 getByIdAndFileUuid 相关逻辑。
     * @return 处理结果
     */
    @Select("select id, file_uuid as fileUuid, version_no as versionNo, minio_version_id as minioVersionId, file_name as fileName, " +
            "file_hash as fileHash, file_md5 as fileMd5, file_type as fileType, file_size as fileSize, change_note as changeNote, " +
            "created_by as createdBy, is_current as current, status, createtime from file_version " +
            "where id = #{id} and file_uuid = #{fileUuid} and status = 1")
    FileVersion getByIdAndFileUuid(@Param("id") Long id, @Param("fileUuid") String fileUuid);

    /**
     * 查询 getMaxVersionNo 相关逻辑。
     * @return 影响行数
     */
    @Select("select coalesce(max(version_no),0) from file_version where file_uuid = #{fileUuid} and status = 1")
    int getMaxVersionNo(@Param("fileUuid") String fileUuid);

    /**
     * 执行 clearCurrent 函数的业务处理。
     * @return 影响行数
     */
    @Update("update file_version set is_current = 0 where file_uuid = #{fileUuid} and status = 1")
    int clearCurrent(@Param("fileUuid") String fileUuid);
}
