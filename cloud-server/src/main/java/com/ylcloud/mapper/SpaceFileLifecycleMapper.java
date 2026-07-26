package com.ylcloud.mapper;

import com.ylcloud.entity.SpaceFile;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;

@Mapper
public interface SpaceFileLifecycleMapper {
    @Update("update space_file set knowledge_state='INDEX_PENDING',knowledge_version=knowledge_version+1," +
            "searchable=0,last_knowledge_error=null,removed_at=null,updatetime=#{now} " +
            "where id=#{fileId} and space_id=#{spaceId} and is_dir=0 and status=1")
    int beginIndex(@Param("spaceId") Long spaceId,@Param("fileId") Long fileId,@Param("now") LocalDateTime now);

    @Select("select id,space_id as spaceId,file_uuid as fileUuid,file_name as fileName,is_dir as dir,parent_id as parentId," +
            "path,version_enabled as versionEnabled,knowledge_state as knowledgeState,knowledge_version as knowledgeVersion," +
            "searchable,last_knowledge_error as lastKnowledgeError,removed_at as removedAt,status,created_by as createdBy,createtime,updatetime " +
            "from space_file where id=#{fileId} and space_id=#{spaceId}")
    SpaceFile getAny(@Param("spaceId") Long spaceId,@Param("fileId") Long fileId);

    @Update("update space_file set knowledge_state='INDEXING',searchable=0,last_knowledge_error=null,updatetime=#{now} " +
            "where id=#{fileId} and space_id=#{spaceId} and status=1 and knowledge_state in ('INDEX_PENDING','FAILED')")
    int markIndexing(@Param("spaceId") Long spaceId,@Param("fileId") Long fileId,@Param("now") LocalDateTime now);

    @Update("update space_file set knowledge_state='READY',searchable=1,last_knowledge_error=null,updatetime=#{now} " +
            "where id=#{fileId} and space_id=#{spaceId} and status=1 and knowledge_state in ('INDEX_PENDING','INDEXING','FAILED')")
    int markReady(@Param("spaceId") Long spaceId,@Param("fileId") Long fileId,@Param("now") LocalDateTime now);

    @Update("update space_file set knowledge_state='FAILED',searchable=0,last_knowledge_error=#{error},updatetime=#{now} " +
            "where id=#{fileId} and space_id=#{spaceId} and status=1 and knowledge_state not in ('REMOVAL_PENDING','REMOVED')")
    int markFailed(@Param("spaceId") Long spaceId,@Param("fileId") Long fileId,
                   @Param("error") String error,@Param("now") LocalDateTime now);

    @Update("update space_file set knowledge_state='REMOVAL_PENDING',knowledge_version=knowledge_version+1," +
            "searchable=0,last_knowledge_error=null,removed_at=#{now},updatetime=#{now} " +
            "where id=#{fileId} and space_id=#{spaceId} and is_dir=0 and status=1")
    int beginRemoval(@Param("spaceId") Long spaceId,@Param("fileId") Long fileId,@Param("now") LocalDateTime now);

    @Update("update space_file set knowledge_state='REMOVED',searchable=0,last_knowledge_error=null,updatetime=#{now} " +
            "where id=#{fileId} and space_id=#{spaceId} and status=0 and knowledge_state='REMOVAL_PENDING'")
    int markRemoved(@Param("spaceId") Long spaceId,@Param("fileId") Long fileId,@Param("now") LocalDateTime now);

    @Update("update space_file set last_knowledge_error=#{error},updatetime=#{now} " +
            "where id=#{fileId} and space_id=#{spaceId} and knowledge_state='REMOVAL_PENDING'")
    int markRemovalFailed(@Param("spaceId") Long spaceId,@Param("fileId") Long fileId,
                          @Param("error") String error,@Param("now") LocalDateTime now);

    @Insert("insert ignore into physical_file_reference(file_uuid,reference_type,reference_id,owner_user_id,space_id,active,created_at) " +
            "values(#{fileUuid},'SPACE_FILE',#{fileId},#{userId},#{spaceId},1,#{now})")
    int addReference(@Param("fileUuid") String fileUuid,@Param("fileId") Long fileId,
                     @Param("userId") Long userId,@Param("spaceId") Long spaceId,@Param("now") LocalDateTime now);

    @Update("update physical_file_reference set active=0,released_at=#{now} " +
            "where reference_type='SPACE_FILE' and reference_id=#{fileId} and active=1")
    int releaseReference(@Param("fileId") Long fileId,@Param("now") LocalDateTime now);

    @Insert("insert ignore into physical_file_reference(file_uuid,reference_type,reference_id,owner_user_id,space_id,active,created_at) " +
            "values(#{fileUuid},'USER_FILE',#{fileId},#{userId},null,1,#{now})")
    int addUserReference(@Param("fileUuid") String fileUuid,@Param("fileId") Long fileId,
                         @Param("userId") Long userId,@Param("now") LocalDateTime now);

    @Update("update physical_file_reference set active=0,released_at=#{now} " +
            "where reference_type='USER_FILE' and reference_id=#{fileId} and active=1")
    int releaseUserReference(@Param("fileId") Long fileId,@Param("now") LocalDateTime now);

    @Select("select count(*) from physical_file_reference where file_uuid=#{fileUuid} and active=1")
    int countActiveReferences(@Param("fileUuid") String fileUuid);

    @Insert("insert ignore into space_file_lifecycle_outbox(event_id,event_key,event_type,space_id,space_file_id,file_uuid," +
            "resource_version,payload_json,status,retry_count,created_at,updated_at) values(" +
            "#{eventId},#{eventKey},#{eventType},#{spaceId},#{fileId},#{fileUuid},#{version},#{payload},'PENDING',0,#{now},#{now})")
    int insertEvent(@Param("eventId") String eventId,@Param("eventKey") String eventKey,
                    @Param("eventType") String eventType,@Param("spaceId") Long spaceId,
                    @Param("fileId") Long fileId,@Param("fileUuid") String fileUuid,
                    @Param("version") Long version,@Param("payload") String payload,@Param("now") LocalDateTime now);

    @Update("update space_file_lifecycle_outbox set status='DONE',last_error=null,updated_at=#{now} " +
            "where space_file_id=#{fileId} and event_type=#{eventType} and resource_version=#{version} and status in ('PENDING','PROCESSING')")
    int completeEvent(@Param("fileId") Long fileId,@Param("eventType") String eventType,
                      @Param("version") Long version,@Param("now") LocalDateTime now);

    @Update("update space_file_lifecycle_outbox set status='DONE',last_error='SUPERSEDED_BY_REMOVAL',updated_at=#{now} " +
            "where space_file_id=#{fileId} and event_type='INDEX_REQUESTED' and resource_version < #{version} " +
            "and status in ('PENDING','PROCESSING','FAILED')")
    int supersedeIndexEvents(@Param("fileId") Long fileId,@Param("version") Long version,
                             @Param("now") LocalDateTime now);

    @Update("update space_file_lifecycle_outbox set status='FAILED',last_error=#{error},retry_count=retry_count+1,updated_at=#{now} " +
            "where space_file_id=#{fileId} and event_type=#{eventType} and resource_version=#{version} and status in ('PENDING','PROCESSING')")
    int failEvent(@Param("fileId") Long fileId,@Param("eventType") String eventType,
                  @Param("version") Long version,@Param("error") String error,@Param("now") LocalDateTime now);
}
