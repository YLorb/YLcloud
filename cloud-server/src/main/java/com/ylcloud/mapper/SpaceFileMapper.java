package com.ylcloud.mapper;

import com.ylcloud.entity.SpaceFile;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 空间文件树表 Mapper。
 */
@Mapper
public interface SpaceFileMapper {

    /**
     * 新增 insert 相关逻辑。
     * @return 影响行数
     */
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("insert into space_file(space_id, file_uuid, file_name, is_dir, parent_id, path, version_enabled, status, created_by, createtime, updatetime) " +
            "values(#{spaceId}, #{fileUuid}, #{fileName}, #{dir}, #{parentId}, #{path}, #{versionEnabled}, #{status}, #{createdBy}, #{createtime}, #{updatetime})")
    int insert(SpaceFile spaceFile);

    /**
     * 查询 getById 相关逻辑。
     * @return 处理结果
     */
    @Select("select id, space_id as spaceId, file_uuid as fileUuid, file_name as fileName, is_dir as dir, " +
            "parent_id as parentId, path, version_enabled as versionEnabled, knowledge_state as knowledgeState, knowledge_version as knowledgeVersion, searchable, last_knowledge_error as lastKnowledgeError, removed_at as removedAt, status, created_by as createdBy, createtime, updatetime " +
            "from space_file where id = #{fileId} and space_id = #{spaceId} and status = 1")
    SpaceFile getById(@Param("spaceId") Long spaceId, @Param("fileId") Long fileId);

    @Select("select id, space_id as spaceId, file_uuid as fileUuid, file_name as fileName, is_dir as dir, " +
            "parent_id as parentId, path, version_enabled as versionEnabled, knowledge_state as knowledgeState, knowledge_version as knowledgeVersion, searchable, last_knowledge_error as lastKnowledgeError, removed_at as removedAt, status, created_by as createdBy, createtime, updatetime " +
            "from space_file where id = #{fileId} and space_id = #{spaceId} for update")
    SpaceFile lockById(@Param("spaceId") Long spaceId, @Param("fileId") Long fileId);

    @Select("select id, space_id as spaceId, file_uuid as fileUuid, file_name as fileName, is_dir as dir, " +
            "parent_id as parentId, path, version_enabled as versionEnabled, knowledge_state as knowledgeState, knowledge_version as knowledgeVersion, searchable, last_knowledge_error as lastKnowledgeError, removed_at as removedAt, status, created_by as createdBy, createtime, updatetime " +
            "from space_file where id = #{fileId} and status = 1")
    SpaceFile getByIdAny(@Param("fileId") Long fileId);

    @Select("select id, space_id as spaceId, file_uuid as fileUuid, file_name as fileName, is_dir as dir, " +
            "parent_id as parentId, path, version_enabled as versionEnabled, knowledge_state as knowledgeState, knowledge_version as knowledgeVersion, searchable, last_knowledge_error as lastKnowledgeError, removed_at as removedAt, status, created_by as createdBy, createtime, updatetime " +
            "from space_file where space_id = #{spaceId} and file_uuid = #{fileUuid} and status = 1 limit 1")
    SpaceFile getActiveByFileUuid(@Param("spaceId") Long spaceId, @Param("fileUuid") String fileUuid);

    /**
     * 查询 listByParentId 相关逻辑。
     * @return 列表结果
     */
    @Select("select id, space_id as spaceId, file_uuid as fileUuid, file_name as fileName, is_dir as dir, " +
            "parent_id as parentId, path, version_enabled as versionEnabled, knowledge_state as knowledgeState, knowledge_version as knowledgeVersion, searchable, last_knowledge_error as lastKnowledgeError, removed_at as removedAt, status, created_by as createdBy, createtime, updatetime " +
            "from space_file where space_id = #{spaceId} and parent_id = #{parentId} and status = 1 " +
            "order by is_dir desc, updatetime desc")
    List<SpaceFile> listByParentId(@Param("spaceId") Long spaceId, @Param("parentId") Long parentId);

    /**
     * 查询 listAll 相关逻辑。
     * @return 列表结果
     */
    @Select("select id, space_id as spaceId, file_uuid as fileUuid, file_name as fileName, is_dir as dir, " +
            "parent_id as parentId, path, version_enabled as versionEnabled, knowledge_state as knowledgeState, knowledge_version as knowledgeVersion, searchable, last_knowledge_error as lastKnowledgeError, removed_at as removedAt, status, created_by as createdBy, createtime, updatetime " +
            "from space_file where space_id = #{spaceId} and status = 1 order by parent_id, is_dir desc, updatetime desc")
    List<SpaceFile> listAll(@Param("spaceId") Long spaceId);

    /**
     * 统计 countSameName 相关逻辑。
     * @return 影响行数
     */
    @Select("select count(1) from space_file where space_id = #{spaceId} and parent_id = #{parentId} " +
            "and file_name = #{fileName} and is_dir = #{dir} and status = 1")
    int countSameName(@Param("spaceId") Long spaceId,
                      @Param("parentId") Long parentId,
                      @Param("fileName") String fileName,
                      @Param("dir") Integer dir);

    @Select("select count(1) from space_file where space_id = #{spaceId} and parent_id = #{parentId} " +
            "and file_name = #{fileName} and is_dir = #{dir} and status = 1 and id <> #{excludeId}")
    int countSameNameExcluding(@Param("spaceId") Long spaceId,
                               @Param("parentId") Long parentId,
                               @Param("fileName") String fileName,
                               @Param("dir") Integer dir,
                               @Param("excludeId") Long excludeId);

    /**
     * 更新 updateName 相关逻辑。
     * @return 影响行数
     */
    @Update("update space_file set file_name = #{fileName}, path = #{path}, updatetime = #{updateTime} " +
            "where id = #{fileId} and space_id = #{spaceId} and status = 1")
    int updateName(@Param("spaceId") Long spaceId,
                   @Param("fileId") Long fileId,
                   @Param("fileName") String fileName,
                   @Param("path") String path,
                   @Param("updateTime") LocalDateTime updateTime);

    /**
     * 执行 disable 函数的业务处理。
     * @return 影响行数
     */
    @Update("update space_file set status = 0, updatetime = #{updateTime} where id = #{fileId} and space_id = #{spaceId} and status = 1")
    int disable(@Param("spaceId") Long spaceId,
                @Param("fileId") Long fileId,
                @Param("updateTime") LocalDateTime updateTime);

    /**
     * 更新 updateVersionEnabled 相关逻辑。
     * @return 影响行数
     */
    @Update("update space_file set version_enabled = #{versionEnabled}, updatetime = #{updateTime} where id = #{fileId} and space_id = #{spaceId} and status = 1")
    int updateVersionEnabled(@Param("spaceId") Long spaceId,
                             @Param("fileId") Long fileId,
                             @Param("versionEnabled") Integer versionEnabled,
                             @Param("updateTime") LocalDateTime updateTime);

    /**
     * 更新 updateFileName 相关逻辑。
     * @return 影响行数
     */
    @Update("update space_file set file_name = #{fileName}, updatetime = #{updateTime} where id = #{fileId} and space_id = #{spaceId} and status = 1")
    int updateFileName(@Param("spaceId") Long spaceId,
                       @Param("fileId") Long fileId,
                       @Param("fileName") String fileName,
                       @Param("updateTime") LocalDateTime updateTime);

    @Update("update space_file set status = 0, removed_at = #{now}, updatetime = #{now} " +
            "where space_id = #{spaceId} and file_uuid = #{fileUuid} and status = 1")
    int disableAllByFileUuid(@Param("spaceId") Long spaceId, @Param("fileUuid") String fileUuid,
                             @Param("now") LocalDateTime now);

    @Update("update space_file set status = 0, file_name = concat('deleted-', id), path = null, " +
            "searchable = 0, last_knowledge_error = null, removed_at = #{now}, updatetime = #{now} " +
            "where space_id = #{spaceId}")
    int purgeBySpaceId(@Param("spaceId") Long spaceId, @Param("now") LocalDateTime now);
}
