package com.ylcloud.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface SpaceDissolutionOutboxMapper {
    @Insert("insert into space_dissolution_outbox(event_id, space_id, owner_id, event_type, status, retry_count, createtime, updatetime) " +
            "values(#{eventId}, #{spaceId}, #{ownerId}, 'SPACE_DISSOLUTION_REQUESTED', 'PENDING', 0, #{now}, #{now})")
    int insertRequested(@Param("eventId") String eventId,
                        @Param("spaceId") Long spaceId,
                        @Param("ownerId") Long ownerId,
                        @Param("now") LocalDateTime now);
}
