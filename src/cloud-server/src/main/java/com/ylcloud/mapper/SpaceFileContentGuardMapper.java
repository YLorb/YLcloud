package com.ylcloud.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface SpaceFileContentGuardMapper {
    @Insert("insert into space_file_content_guard(space_id,content_hash,space_file_id,guard_state,created_at,updated_at) " +
            "values(#{spaceId},#{contentHash},null,'RESERVED',#{now},#{now})")
    int reserve(@Param("spaceId") Long spaceId, @Param("contentHash") String contentHash,
                @Param("now") LocalDateTime now);

    @Update("update space_file_content_guard set space_file_id=#{spaceFileId},guard_state='ACTIVE',updated_at=#{now} " +
            "where space_id=#{spaceId} and content_hash=#{contentHash} and guard_state='RESERVED'")
    int activate(@Param("spaceId") Long spaceId, @Param("contentHash") String contentHash,
                 @Param("spaceFileId") Long spaceFileId, @Param("now") LocalDateTime now);

    @Delete("delete from space_file_content_guard where space_id=#{spaceId} and content_hash=#{contentHash} " +
            "and (space_file_id=#{spaceFileId} or guard_state='RESERVED')")
    int release(@Param("spaceId") Long spaceId, @Param("contentHash") String contentHash,
                @Param("spaceFileId") Long spaceFileId);

    @Update("update space_file_content_guard set space_file_id=#{spaceFileId},guard_state='ACTIVE',updated_at=#{now} " +
            "where space_id=#{spaceId} and content_hash=#{contentHash}")
    int reassign(@Param("spaceId") Long spaceId, @Param("contentHash") String contentHash,
                 @Param("spaceFileId") Long spaceFileId, @Param("now") LocalDateTime now);
}
