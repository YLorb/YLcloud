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

    /** Serializes subtree mutations with any insert/move whose parent row is in this Space. */
    @Select("select id from space_file where space_id=#{spaceId} and status=1 for update")
    List<Long> lockActiveSpaceNodes(@Param("spaceId") Long spaceId);

    /**
     * 新增 insert 相关逻辑。
     * @return 影响行数
     */
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("insert into space_file(space_id,file_uuid,file_name,is_dir,parent_id,path,node_version,depth,content_hash,lifecycle_state,version_enabled,status,created_by,createtime,updatetime) " +
            "values(#{spaceId},#{fileUuid},#{fileName},#{dir},#{parentId},#{path},coalesce(#{nodeVersion},1),coalesce(#{depth},0),#{contentHash},coalesce(#{lifecycleState},'ACTIVE'),#{versionEnabled},#{status},#{createdBy},#{createtime},#{updatetime})")
    int insert(SpaceFile spaceFile);

    /**
     * 查询 getById 相关逻辑。
     * @return 处理结果
     */
    @Select("select id, space_id as spaceId, file_uuid as fileUuid, file_name as fileName, is_dir as dir, " +
            "parent_id as parentId,path,node_version as nodeVersion,depth,content_hash as contentHash,lifecycle_state as lifecycleState,deletion_batch_id as deletionBatchId,legacy_duplicate as legacyDuplicate,version_enabled as versionEnabled, knowledge_state as knowledgeState, knowledge_version as knowledgeVersion, searchable, last_knowledge_error as lastKnowledgeError, removed_at as removedAt, status, created_by as createdBy, createtime, updatetime " +
            "from space_file where id = #{fileId} and space_id = #{spaceId} and status = 1")
    SpaceFile getById(@Param("spaceId") Long spaceId, @Param("fileId") Long fileId);

    @Select("select id, space_id as spaceId, file_uuid as fileUuid, file_name as fileName, is_dir as dir, " +
            "parent_id as parentId,path,node_version as nodeVersion,depth,content_hash as contentHash,lifecycle_state as lifecycleState,deletion_batch_id as deletionBatchId,legacy_duplicate as legacyDuplicate,version_enabled as versionEnabled, knowledge_state as knowledgeState, knowledge_version as knowledgeVersion, searchable, last_knowledge_error as lastKnowledgeError, removed_at as removedAt, status, created_by as createdBy, createtime, updatetime " +
            "from space_file where id = #{fileId} and space_id = #{spaceId} for update")
    SpaceFile lockById(@Param("spaceId") Long spaceId, @Param("fileId") Long fileId);

    @Select("select id, space_id as spaceId, file_uuid as fileUuid, file_name as fileName, is_dir as dir, " +
            "parent_id as parentId,path,node_version as nodeVersion,depth,content_hash as contentHash,lifecycle_state as lifecycleState,deletion_batch_id as deletionBatchId,legacy_duplicate as legacyDuplicate,version_enabled as versionEnabled, knowledge_state as knowledgeState, knowledge_version as knowledgeVersion, searchable, last_knowledge_error as lastKnowledgeError, removed_at as removedAt, status, created_by as createdBy, createtime, updatetime " +
            "from space_file where id = #{fileId} and status = 1")
    SpaceFile getByIdAny(@Param("fileId") Long fileId);

    @Select("select id, space_id as spaceId, file_uuid as fileUuid, file_name as fileName, is_dir as dir, " +
            "parent_id as parentId,path,node_version as nodeVersion,depth,content_hash as contentHash,lifecycle_state as lifecycleState,deletion_batch_id as deletionBatchId,legacy_duplicate as legacyDuplicate,version_enabled as versionEnabled, knowledge_state as knowledgeState, knowledge_version as knowledgeVersion, searchable, last_knowledge_error as lastKnowledgeError, removed_at as removedAt, status, created_by as createdBy, createtime, updatetime " +
            "from space_file where space_id = #{spaceId} and file_uuid = #{fileUuid} and status = 1 limit 1")
    SpaceFile getActiveByFileUuid(@Param("spaceId") Long spaceId, @Param("fileUuid") String fileUuid);

    @Select("select id,space_id as spaceId,file_uuid as fileUuid,file_name as fileName,is_dir as dir,parent_id as parentId,path," +
            "node_version as nodeVersion,depth,content_hash as contentHash,lifecycle_state as lifecycleState,created_by as createdBy " +
            "from space_file where space_id=#{spaceId} and content_hash=#{contentHash} and is_dir=0 and status=1 " +
            "and lifecycle_state in ('ACTIVE','REMOVAL_PENDING') order by legacy_duplicate,id limit 1")
    SpaceFile findByContentHash(@Param("spaceId") Long spaceId, @Param("contentHash") String contentHash);

    @Select("select id,space_id as spaceId,file_uuid as fileUuid,file_name as fileName,is_dir as dir,parent_id as parentId,path," +
            "node_version as nodeVersion,depth,content_hash as contentHash,lifecycle_state as lifecycleState,legacy_duplicate as legacyDuplicate," +
            "created_by as createdBy,status,createtime,updatetime from space_file " +
            "where space_id=#{spaceId} and status=1 and legacy_duplicate=1 order by content_hash,id")
    List<SpaceFile> listLegacyDuplicates(@Param("spaceId") Long spaceId);

    /**
     * 查询 listByParentId 相关逻辑。
     * @return 列表结果
     */
    @Select("select id, space_id as spaceId, file_uuid as fileUuid, file_name as fileName, is_dir as dir, " +
            "parent_id as parentId,path,node_version as nodeVersion,depth,content_hash as contentHash,lifecycle_state as lifecycleState,deletion_batch_id as deletionBatchId,legacy_duplicate as legacyDuplicate,version_enabled as versionEnabled, knowledge_state as knowledgeState, knowledge_version as knowledgeVersion, searchable, last_knowledge_error as lastKnowledgeError, removed_at as removedAt, status, created_by as createdBy, createtime, updatetime " +
            "from space_file where space_id = #{spaceId} and parent_id = #{parentId} and status = 1 and lifecycle_state='ACTIVE' " +
            "order by is_dir desc, updatetime desc")
    List<SpaceFile> listByParentId(@Param("spaceId") Long spaceId, @Param("parentId") Long parentId);

    /**
     * 查询 listAll 相关逻辑。
     * @return 列表结果
     */
    @Select("select id, space_id as spaceId, file_uuid as fileUuid, file_name as fileName, is_dir as dir, " +
            "parent_id as parentId,path,node_version as nodeVersion,depth,content_hash as contentHash,lifecycle_state as lifecycleState,deletion_batch_id as deletionBatchId,legacy_duplicate as legacyDuplicate,version_enabled as versionEnabled, knowledge_state as knowledgeState, knowledge_version as knowledgeVersion, searchable, last_knowledge_error as lastKnowledgeError, removed_at as removedAt, status, created_by as createdBy, createtime, updatetime " +
            "from space_file where space_id = #{spaceId} and status = 1 and lifecycle_state='ACTIVE' order by parent_id, is_dir desc, updatetime desc")
    List<SpaceFile> listAll(@Param("spaceId") Long spaceId);

    @Select("select file_uuid from space_file where space_id=#{spaceId} and status=1 and is_dir=0 " +
            "and file_uuid is not null order by id for update")
    List<String> listActiveFileUuidsForCleanup(@Param("spaceId") Long spaceId);

    @Select("select id, space_id as spaceId, file_uuid as fileUuid, file_name as fileName, is_dir as dir, " +
            "parent_id as parentId,path,node_version as nodeVersion,depth,content_hash as contentHash,lifecycle_state as lifecycleState,deletion_batch_id as deletionBatchId,legacy_duplicate as legacyDuplicate,version_enabled as versionEnabled, knowledge_state as knowledgeState, knowledge_version as knowledgeVersion, searchable, last_knowledge_error as lastKnowledgeError, removed_at as removedAt, status, created_by as createdBy, createtime, updatetime " +
            "from space_file where space_id=#{spaceId} and status=1 and lifecycle_state='ACTIVE' " +
            "and lower(file_name) like concat('%',lower(#{query}),'%') order by is_dir desc,updatetime desc limit #{limit}")
    List<SpaceFile> search(@Param("spaceId") Long spaceId,
                           @Param("query") String query,
                           @Param("limit") int limit);

    @Select("with recursive subtree as (" +
            "select id,parent_id,created_by,depth from space_file where space_id=#{spaceId} and id=#{rootId} and status=1 " +
            "union all select sf.id,sf.parent_id,sf.created_by,sf.depth from space_file sf join subtree st on sf.parent_id=st.id " +
            "where sf.space_id=#{spaceId} and sf.status=1) " +
            "select count(*) from subtree where created_by<>#{userId}")
    int countForeignOwnedInSubtree(@Param("spaceId") Long spaceId,
                                    @Param("rootId") Long rootId,
                                    @Param("userId") Long userId);

    @Select("with recursive subtree as (" +
            "select id,parent_id,depth from space_file where space_id=#{spaceId} and id=#{rootId} and status=1 " +
            "union all select sf.id,sf.parent_id,sf.depth from space_file sf join subtree st on sf.parent_id=st.id " +
            "where sf.space_id=#{spaceId} and sf.status=1) select coalesce(max(depth),0) from subtree")
    int maxDepthInSubtree(@Param("spaceId") Long spaceId, @Param("rootId") Long rootId);

    @Select("with recursive subtree as (" +
            "select id,parent_id from space_file where space_id=#{spaceId} and id=#{rootId} and status=1 " +
            "union all select sf.id,sf.parent_id from space_file sf join subtree st on sf.parent_id=st.id " +
            "where sf.space_id=#{spaceId} and sf.status=1) select count(*) from subtree where id=#{candidateId}")
    int countInSubtree(@Param("spaceId") Long spaceId,
                       @Param("rootId") Long rootId,
                       @Param("candidateId") Long candidateId);

    @Select("select id,space_id as spaceId,file_uuid as fileUuid,file_name as fileName,is_dir as dir,parent_id as parentId,path," +
            "node_version as nodeVersion,depth,content_hash as contentHash,lifecycle_state as lifecycleState,deletion_batch_id as deletionBatchId," +
            "legacy_duplicate as legacyDuplicate,knowledge_state as knowledgeState,searchable,created_by as createdBy,status from space_file " +
            "where space_id=#{spaceId} and status=1 and (id=#{rootId} or (right(#{rootPath},1)='/' and path like concat(#{rootPath},'%'))) order by depth desc,id desc")
    List<SpaceFile> listSubtree(@Param("spaceId") Long spaceId,@Param("rootId") Long rootId,@Param("rootPath") String rootPath);

    @Select("select id,space_id as spaceId,file_uuid as fileUuid,file_name as fileName,is_dir as dir,parent_id as parentId,path," +
            "node_version as nodeVersion,depth,content_hash as contentHash,lifecycle_state as lifecycleState,deletion_batch_id as deletionBatchId," +
            "legacy_duplicate as legacyDuplicate,knowledge_state as knowledgeState,searchable,created_by as createdBy,status from space_file " +
            "where deletion_batch_id=#{batchId} and status=1 order by depth desc,id desc")
    List<SpaceFile> listByDeletionBatch(@Param("batchId") Long batchId);

    @Update("update space_file set lifecycle_state='REMOVAL_PENDING',deletion_batch_id=#{batchId},node_version=node_version+1,updatetime=#{now} " +
            "where space_id=#{spaceId} and status=1 and lifecycle_state='ACTIVE' and (id=#{rootId} or (right(#{rootPath},1)='/' and path like concat(#{rootPath},'%')))")
    int isolateSubtree(@Param("spaceId") Long spaceId,@Param("rootId") Long rootId,@Param("rootPath") String rootPath,
                       @Param("batchId") Long batchId,@Param("now") LocalDateTime now);

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
    @Update("update space_file set file_name=#{fileName},path=#{path},node_version=node_version+1,updatetime=#{updateTime} " +
            "where id=#{fileId} and space_id=#{spaceId} and status=1 and lifecycle_state='ACTIVE' and node_version=#{expectedVersion}")
    int updateName(@Param("spaceId") Long spaceId,
                   @Param("fileId") Long fileId,
                   @Param("fileName") String fileName,
                   @Param("path") String path,
                   @Param("expectedVersion") Long expectedVersion,
                   @Param("updateTime") LocalDateTime updateTime);

    @Update("update space_file set parent_id=#{parentId},path=#{path},depth=#{depth},node_version=node_version+1,updatetime=#{updateTime} " +
            "where id=#{fileId} and space_id=#{spaceId} and status=1 and lifecycle_state='ACTIVE' and node_version=#{expectedVersion}")
    int move(@Param("spaceId") Long spaceId,
             @Param("fileId") Long fileId,
             @Param("parentId") Long parentId,
             @Param("path") String path,
             @Param("depth") Integer depth,
             @Param("expectedVersion") Long expectedVersion,
             @Param("updateTime") LocalDateTime updateTime);

    @Update("update space_file set path=concat(#{newPrefix},substring(path,char_length(#{oldPrefix})+1))," +
            "depth=depth+#{depthDelta},node_version=node_version+1,updatetime=#{updateTime} " +
            "where space_id=#{spaceId} and status=1 and id<>#{rootId} and path like concat(#{oldPrefix},'%')")
    int updateDescendantLocations(@Param("spaceId") Long spaceId,
                                  @Param("rootId") Long rootId,
                                  @Param("oldPrefix") String oldPrefix,
                                  @Param("newPrefix") String newPrefix,
                                  @Param("depthDelta") Integer depthDelta,
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

    @Update("update space_file set content_hash=#{contentHash},node_version=node_version+1,updatetime=#{now} " +
            "where space_id=#{spaceId} and id=#{fileId} and status=1")
    int updateContentHash(@Param("spaceId") Long spaceId,@Param("fileId") Long fileId,
                          @Param("contentHash") String contentHash,@Param("now") LocalDateTime now);

    @Update("update space_file set status = 0, removed_at = #{now}, updatetime = #{now} " +
            "where space_id = #{spaceId} and file_uuid = #{fileUuid} and status = 1")
    int disableAllByFileUuid(@Param("spaceId") Long spaceId, @Param("fileUuid") String fileUuid,
                             @Param("now") LocalDateTime now);

    @Update("update space_file set status = 0, file_name = concat('deleted-', id), path = null, " +
            "searchable = 0, last_knowledge_error = null, removed_at = #{now}, updatetime = #{now} " +
            "where space_id = #{spaceId}")
    int purgeBySpaceId(@Param("spaceId") Long spaceId, @Param("now") LocalDateTime now);
}
