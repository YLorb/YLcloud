package com.ylcloud.mapper;

import com.ylcloud.entity.UserMemoryItem;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UserMemoryItemMapper {
    String COLUMNS = "id, user_id as userId, source_session_id as sourceSessionId, source_message_id as sourceMessageId, " +
            "memory_type as memoryType, content, normalized_key as normalizedKey, content_hash as contentHash, source_hash as sourceHash, " +
            "confidence, user_confirmed as userConfirmed, pinned, expires_at as expiresAt, version, memory_status as memoryStatus, " +
            "embedding_status as embeddingStatus, qdrant_point_id as qdrantPointId, supersedes_id as supersedesId, retry_count as retryCount, " +
            "next_retry_time as nextRetryTime, error_message as errorMessage, origin_async_task_id as originAsyncTaskId, " +
            "profile_async_task_id as profileAsyncTaskId, vector_async_task_id as vectorAsyncTaskId, async_version as asyncVersion, " +
            "status, createtime, updatetime";

    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("insert into user_memory_item(user_id, source_session_id, source_message_id, memory_type, content, normalized_key, " +
            "content_hash, source_hash, confidence, user_confirmed, pinned, expires_at, version, memory_status, embedding_status, " +
            "supersedes_id, retry_count, origin_async_task_id, async_version, status, createtime, updatetime) values(#{userId},#{sourceSessionId},#{sourceMessageId}," +
            "#{memoryType},#{content},#{normalizedKey},#{contentHash},#{sourceHash},#{confidence},#{userConfirmed},#{pinned}," +
            "#{expiresAt},#{version},#{memoryStatus},#{embeddingStatus},#{supersedesId},0,#{originAsyncTaskId},#{asyncVersion},1,#{createtime},#{updatetime})")
    int insert(UserMemoryItem item);

    @Select("select " + COLUMNS + " from user_memory_item where user_id=#{userId} and normalized_key=#{key} and source_hash=#{sourceHash} limit 1")
    UserMemoryItem findIdempotent(@Param("userId") Long userId, @Param("key") String key, @Param("sourceHash") String sourceHash);

    @Select("select " + COLUMNS + " from user_memory_item where user_id=#{userId} and normalized_key=#{key} " +
            "and memory_status='ACTIVE' and status=1 and (expires_at is null or expires_at > now()) order by version desc,id desc limit 1")
    UserMemoryItem findActiveByKey(@Param("userId") Long userId, @Param("key") String key);

    @Select("select " + COLUMNS + " from user_memory_item where id=#{id}")
    UserMemoryItem getById(@Param("id") Long id);

    @Select("select " + COLUMNS + " from user_memory_item where embedding_status in ('PENDING','FAILED_RETRYABLE') " +
            "and memory_status='CANDIDATE' and status=1 and retry_count < #{maxRetries} and (next_retry_time is null or next_retry_time <= now()) order by id limit #{limit}")
    List<UserMemoryItem> listPendingIndex(@Param("limit") int limit, @Param("maxRetries") int maxRetries);

    @Update("update user_memory_item set embedding_status='INDEXING', memory_status='INDEXING', error_message=null, updatetime=#{now} " +
            "where id=#{id} and embedding_status in ('PENDING','FAILED_RETRYABLE') and memory_status='CANDIDATE' and status=1")
    int claimIndex(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Update("update user_memory_item set embedding_status='READY', memory_status='ACTIVE', qdrant_point_id=#{pointId}, " +
            "error_message=null, next_retry_time=null, updatetime=#{now} where id=#{id} and embedding_status='INDEXING' and memory_status='INDEXING'")
    int activate(@Param("id") Long id, @Param("pointId") String pointId, @Param("now") LocalDateTime now);

    @Update("update user_memory_item set profile_async_task_id=#{asyncTaskId},updatetime=#{now} " +
            "where id=#{id} and async_version=#{version} and status=1 and memory_status='CANDIDATE' " +
            "and embedding_status in ('PENDING','FAILED_RETRYABLE') and (profile_async_task_id is null or profile_async_task_id=#{asyncTaskId})")
    int bindProfileTask(@Param("id") Long id,@Param("version") long version,
                        @Param("asyncTaskId") Long asyncTaskId,@Param("now") LocalDateTime now);

    @Update("update user_memory_item set vector_async_task_id=#{vectorTaskId},updatetime=#{now} " +
            "where id=#{id} and async_version=#{version} and profile_async_task_id=#{profileTaskId} and status=1 " +
            "and memory_status='CANDIDATE' and embedding_status in ('PENDING','FAILED_RETRYABLE') " +
            "and (vector_async_task_id is null or vector_async_task_id=#{vectorTaskId})")
    int bindVectorTask(@Param("id") Long id,@Param("version") long version,
                       @Param("profileTaskId") Long profileTaskId,@Param("vectorTaskId") Long vectorTaskId,
                       @Param("now") LocalDateTime now);

    @Update("update user_memory_item set embedding_status='INDEXING',memory_status='INDEXING',error_message=null,updatetime=#{now} " +
            "where id=#{id} and async_version=#{version} and vector_async_task_id=#{asyncTaskId} and status=1 " +
            "and embedding_status in ('PENDING','FAILED_RETRYABLE','INDEXING') and memory_status in ('CANDIDATE','INDEXING')")
    int claimIndexAsync(@Param("id") Long id,@Param("version") long version,
                        @Param("asyncTaskId") Long asyncTaskId,@Param("now") LocalDateTime now);

    @Update("update user_memory_item set embedding_status='READY',memory_status='ACTIVE',qdrant_point_id=#{pointId}," +
            "error_message=null,next_retry_time=null,updatetime=#{now} where id=#{id} and async_version=#{version} " +
            "and vector_async_task_id=#{asyncTaskId} and embedding_status='INDEXING' and memory_status='INDEXING' and status=1")
    int activateAsync(@Param("id") Long id,@Param("version") long version,@Param("asyncTaskId") Long asyncTaskId,
                      @Param("pointId") String pointId,@Param("now") LocalDateTime now);

    @Update("update user_memory_item set embedding_status='FAILED_RETRYABLE',memory_status='CANDIDATE',retry_count=retry_count+1," +
            "error_message=#{error},updatetime=#{now} where id=#{id} and async_version=#{version} " +
            "and vector_async_task_id=#{asyncTaskId} and embedding_status='INDEXING'")
    int failIndexAsync(@Param("id") Long id,@Param("version") long version,@Param("asyncTaskId") Long asyncTaskId,
                       @Param("error") String error,@Param("now") LocalDateTime now);

    @Update("update user_memory_item set embedding_status='CANCELED',memory_status='CANCELED',async_version=async_version+1," +
            "profile_async_task_id=null,vector_async_task_id=null,error_message='Unified task canceled',updatetime=#{now} " +
            "where id=#{id} and async_version=#{version} and vector_async_task_id=#{asyncTaskId} and embedding_status='INDEXING'")
    int cancelIndexAsync(@Param("id") Long id,@Param("version") long version,
                         @Param("asyncTaskId") Long asyncTaskId,@Param("now") LocalDateTime now);

    @Update("update user_memory_item set embedding_status='FAILED_RETRYABLE', memory_status='CANDIDATE', retry_count=retry_count+1, " +
            "next_retry_time=#{nextRetry}, error_message=#{error}, updatetime=#{now} where id=#{id} and embedding_status='INDEXING'")
    int failIndex(@Param("id") Long id, @Param("error") String error, @Param("nextRetry") LocalDateTime nextRetry,
                  @Param("now") LocalDateTime now);

    @Update("update user_memory_item set memory_status='SUPERSEDED', embedding_status='DELETE_PENDING', async_version=async_version+1, " +
            "profile_async_task_id=null,vector_async_task_id=null,updatetime=#{now} " +
            "where id=#{id} and memory_status='ACTIVE'")
    int supersede(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Select({"<script>select " + COLUMNS + " from user_memory_item where user_id=#{userId} and memory_status='ACTIVE' and status=1 " +
            "and (expires_at is null or expires_at &gt; now()) and id in " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>"})
    List<UserMemoryItem> listActiveByIds(@Param("userId") Long userId, @Param("ids") List<Long> ids);

    @Select("select " + COLUMNS + " from user_memory_item where embedding_status='DELETE_PENDING' order by updatetime limit #{limit}")
    List<UserMemoryItem> listDeletePending(@Param("limit") int limit);

    @Update("update user_memory_item set memory_status='DELETED', embedding_status='DELETED', status=0, error_message=null, updatetime=#{now} where id=#{id}")
    int markDeleted(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Update("update user_memory_item set vector_async_task_id=#{asyncTaskId},updatetime=#{now} where id=#{id} " +
            "and async_version=#{version} and embedding_status='DELETE_PENDING' " +
            "and (vector_async_task_id is null or vector_async_task_id=#{asyncTaskId})")
    int bindDeleteTask(@Param("id") Long id,@Param("version") long version,
                       @Param("asyncTaskId") Long asyncTaskId,@Param("now") LocalDateTime now);

    @Update("update user_memory_item set memory_status='DELETED',embedding_status='DELETED',status=0,error_message=null,updatetime=#{now} " +
            "where id=#{id} and async_version=#{version} and vector_async_task_id=#{asyncTaskId} and embedding_status='DELETE_PENDING'")
    int markDeletedAsync(@Param("id") Long id,@Param("version") long version,
                         @Param("asyncTaskId") Long asyncTaskId,@Param("now") LocalDateTime now);

    @Update("update user_memory_item set retry_count=retry_count+1,error_message=#{error},updatetime=#{now} " +
            "where id=#{id} and async_version=#{version} and vector_async_task_id=#{asyncTaskId} and embedding_status='DELETE_PENDING'")
    int failDeleteAsync(@Param("id") Long id,@Param("version") long version,
                        @Param("asyncTaskId") Long asyncTaskId,@Param("error") String error,@Param("now") LocalDateTime now);

    @Update("update user_memory_item set error_message=#{error}, retry_count=retry_count+1, next_retry_time=#{nextRetry}, updatetime=#{now} where id=#{id} and embedding_status='DELETE_PENDING'")
    int failDelete(@Param("id") Long id, @Param("error") String error, @Param("nextRetry") LocalDateTime nextRetry,
                   @Param("now") LocalDateTime now);

    @Select("select coalesce((select enabled from user_memory_setting where user_id=#{userId}),1)")
    Boolean isEnabled(@Param("userId") Long userId);

    @Select("select " + COLUMNS + " from user_memory_item where user_id=#{userId} and status=1 " +
            "and (#{type} is null or #{type}='' or memory_type=#{type}) and (#{keyword} is null or #{keyword}='' or content like concat('%',#{keyword},'%') or normalized_key like concat('%',#{keyword},'%')) " +
            "and memory_status not in ('DELETED','SUPERSEDED') order by pinned desc, updatetime desc limit #{limit}")
    List<UserMemoryItem> listManaged(@Param("userId") Long userId, @Param("type") String type,
                                     @Param("keyword") String keyword, @Param("limit") int limit);

    @Select("select " + COLUMNS + " from user_memory_item where id=#{id} and user_id=#{userId} and status=1")
    UserMemoryItem getOwned(@Param("id") Long id, @Param("userId") Long userId);

    @Select({"<script>select " + COLUMNS + " from user_memory_item where user_id=#{userId} and status=1 and id in " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "order by normalized_key,version desc,id desc</script>"})
    List<UserMemoryItem> listOwnedVersions(@Param("userId") Long userId, @Param("ids") List<Long> ids);

    @Update("update user_memory_item set pinned=#{pinned}, updatetime=#{now} where id=#{id} and user_id=#{userId} and status=1 and memory_status='ACTIVE'")
    int setPinned(@Param("id") Long id, @Param("userId") Long userId, @Param("pinned") boolean pinned, @Param("now") LocalDateTime now);

    @Update("update user_memory_item set memory_status='DELETE_PENDING', embedding_status='DELETE_PENDING', async_version=async_version+1, " +
            "profile_async_task_id=null,vector_async_task_id=null,updatetime=#{now} " +
            "where id=#{id} and user_id=#{userId} and status=1 and memory_status not in ('DELETED','DELETE_PENDING')")
    int forget(@Param("id") Long id, @Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Update("update user_memory_item set memory_status='DELETE_PENDING', embedding_status='DELETE_PENDING', async_version=async_version+1, " +
            "profile_async_task_id=null,vector_async_task_id=null,updatetime=#{now} " +
            "where user_id=#{userId} and status=1 and memory_status not in ('DELETED','DELETE_PENDING')")
    int clear(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Insert("insert into user_memory_setting(user_id,enabled,retention_days,createtime,updatetime) values(#{userId},#{enabled},#{retentionDays},#{now},#{now}) " +
            "on duplicate key update enabled=values(enabled),retention_days=values(retention_days),updatetime=values(updatetime)")
    int saveSetting(@Param("userId") Long userId, @Param("enabled") boolean enabled,
                    @Param("retentionDays") int retentionDays, @Param("now") LocalDateTime now);

    @Select("select coalesce((select retention_days from user_memory_setting where user_id=#{userId}),#{fallback})")
    Integer retentionDays(@Param("userId") Long userId, @Param("fallback") int fallback);

    @Select("select count(*) from user_memory_item where user_id=#{userId} and status=1 and memory_status='ACTIVE'")
    Integer countActive(@Param("userId") Long userId);
    @Select("select count(*) from user_memory_item where user_id=#{userId} and status=1 and memory_status='ACTIVE' and pinned=1")
    Integer countPinned(@Param("userId") Long userId);
    @Select("select count(*) from user_memory_item where user_id=#{userId} and status=1 and memory_status in ('CANDIDATE','INDEXING')")
    Integer countPending(@Param("userId") Long userId);
    @Select("select count(*) from user_memory_item where user_id=#{userId} and status=1 and embedding_status='FAILED_RETRYABLE'")
    Integer countFailed(@Param("userId") Long userId);
}
