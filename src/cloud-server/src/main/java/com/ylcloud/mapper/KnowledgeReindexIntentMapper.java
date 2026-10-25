package com.ylcloud.mapper;

import com.ylcloud.entity.KnowledgeReindexIntent;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface KnowledgeReindexIntentMapper {
    @Insert("insert into knowledge_reindex_intent(space_id,space_file_id,file_hash,created_by,state,created_at,updated_at) "
            + "values(#{spaceId},#{spaceFileId},#{hash},#{userId},'PENDING',#{now},#{now}) on duplicate key update "
            + "state='PENDING',claim_token=null,created_by=#{userId},next_retry_at=null,updated_at=#{now}")
    int enqueue(@Param("spaceId") Long spaceId,@Param("spaceFileId") Long spaceFileId,@Param("hash") String hash,
                @Param("userId") Long userId,@Param("now") LocalDateTime now);

    @Select("select id,space_id as spaceId,space_file_id as spaceFileId,file_hash as fileHash,created_by as createdBy,"
            + "state,attempts,updated_at as updatedAt from knowledge_reindex_intent "
            + "where state in ('PENDING','RETRY') and (next_retry_at is null or next_retry_at<=#{now}) order by id limit 100")
    List<KnowledgeReindexIntent> pending(@Param("now") LocalDateTime now);

    @Update("update knowledge_reindex_intent set state='RUNNING',claim_token=#{token},attempts=attempts+1,updated_at=#{now} "
            + "where id=#{id} and state in ('PENDING','RETRY')")
    int claim(@Param("id") Long id,@Param("token") String token,@Param("now") LocalDateTime now);

    @Update("update knowledge_reindex_intent set state=#{state},error_message=#{error},next_retry_at=#{retryAt},updated_at=#{now} "
            + "where id=#{id} and state='RUNNING' and claim_token=#{token}")
    int finish(@Param("id") Long id,@Param("token") String token,@Param("state") String state,@Param("error") String error,
               @Param("retryAt") LocalDateTime retryAt,@Param("now") LocalDateTime now);

    @Update("update knowledge_reindex_intent set state='RETRY',claim_token=null,next_retry_at=#{now},updated_at=#{now} "
            + "where state='RUNNING' and updated_at<#{expired}")
    int recover(@Param("expired") LocalDateTime expired,@Param("now") LocalDateTime now);
}
