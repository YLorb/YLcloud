package com.ylcloud.mapper;

import com.ylcloud.entity.SpaceDissolutionEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SpaceDissolutionOutboxMapper {
    @Insert("insert into space_dissolution_outbox(event_id, space_id, owner_id, event_type, status, retry_count, createtime, updatetime) " +
            "values(#{eventId}, #{spaceId}, #{ownerId}, 'SPACE_DISSOLUTION_REQUESTED', 'PENDING', 0, #{now}, #{now})")
    int insertRequested(@Param("eventId") String eventId,
                        @Param("spaceId") Long spaceId,
                        @Param("ownerId") Long ownerId,
                        @Param("now") LocalDateTime now);

    @Select("select id from space_dissolution_outbox where " +
            "((status in ('PENDING','FAILED') and (next_retry_at is null or next_retry_at <= #{now})) " +
            "or (status = 'PROCESSING' and updatetime < #{staleBefore})) order by id limit #{limit}")
    List<Long> listReadyIds(@Param("now") LocalDateTime now,
                            @Param("staleBefore") LocalDateTime staleBefore,
                            @Param("limit") int limit);

    @Update("update space_dissolution_outbox set status='PROCESSING', error_message=null, updatetime=#{now} " +
            "where id=#{id} and ((status in ('PENDING','FAILED') and (next_retry_at is null or next_retry_at <= #{now})) " +
            "or (status='PROCESSING' and updatetime < #{staleBefore}))")
    int claim(@Param("id") Long id, @Param("now") LocalDateTime now,
              @Param("staleBefore") LocalDateTime staleBefore);

    @Select("select id,event_id as eventId,space_id as spaceId,owner_id as ownerId,status,retry_count as retryCount," +
            "next_retry_at as nextRetryAt,error_message as errorMessage,createtime,updatetime " +
            "from space_dissolution_outbox where id=#{id}")
    SpaceDissolutionEvent getById(@Param("id") Long id);

    @Update("update space_dissolution_outbox set status='SUCCEEDED',next_retry_at=null,error_message=null,updatetime=#{now} " +
            "where id=#{id} and status='PROCESSING'")
    int markSucceeded(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Update("update space_dissolution_outbox set status='FAILED',retry_count=retry_count+1," +
            "next_retry_at=#{nextRetryAt},error_message=#{errorMessage},updatetime=#{now} " +
            "where id=#{id} and status='PROCESSING'")
    int markFailed(@Param("id") Long id, @Param("nextRetryAt") LocalDateTime nextRetryAt,
                   @Param("errorMessage") String errorMessage, @Param("now") LocalDateTime now);
}
