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
            "next_retry_time as nextRetryTime, error_message as errorMessage, status, createtime, updatetime";

    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("insert into user_memory_item(user_id, source_session_id, source_message_id, memory_type, content, normalized_key, " +
            "content_hash, source_hash, confidence, user_confirmed, pinned, expires_at, version, memory_status, embedding_status, " +
            "supersedes_id, retry_count, status, createtime, updatetime) values(#{userId},#{sourceSessionId},#{sourceMessageId}," +
            "#{memoryType},#{content},#{normalizedKey},#{contentHash},#{sourceHash},#{confidence},#{userConfirmed},#{pinned}," +
            "#{expiresAt},#{version},#{memoryStatus},#{embeddingStatus},#{supersedesId},0,1,#{createtime},#{updatetime})")
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

    @Update("update user_memory_item set embedding_status='FAILED_RETRYABLE', memory_status='CANDIDATE', retry_count=retry_count+1, " +
            "next_retry_time=#{nextRetry}, error_message=#{error}, updatetime=#{now} where id=#{id} and embedding_status='INDEXING'")
    int failIndex(@Param("id") Long id, @Param("error") String error, @Param("nextRetry") LocalDateTime nextRetry,
                  @Param("now") LocalDateTime now);

    @Update("update user_memory_item set memory_status='SUPERSEDED', embedding_status='DELETE_PENDING', updatetime=#{now} " +
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

    @Update("update user_memory_item set pinned=#{pinned}, updatetime=#{now} where id=#{id} and user_id=#{userId} and status=1 and memory_status='ACTIVE'")
    int setPinned(@Param("id") Long id, @Param("userId") Long userId, @Param("pinned") boolean pinned, @Param("now") LocalDateTime now);

    @Update("update user_memory_item set memory_status='DELETE_PENDING', embedding_status='DELETE_PENDING', updatetime=#{now} " +
            "where id=#{id} and user_id=#{userId} and status=1 and memory_status not in ('DELETED','DELETE_PENDING')")
    int forget(@Param("id") Long id, @Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Update("update user_memory_item set memory_status='DELETE_PENDING', embedding_status='DELETE_PENDING', updatetime=#{now} " +
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
