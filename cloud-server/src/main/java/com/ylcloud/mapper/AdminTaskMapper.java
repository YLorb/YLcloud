package com.ylcloud.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

@Mapper
public interface AdminTaskMapper {
    String FILTER = "<where><choose><when test='archived'>archived_at is not null</when><otherwise>archived_at is null</otherwise></choose>"
            + "<if test='status != null'> and status=#{status}</if>"
            + "<if test='type != null'> and task_type=#{type}</if>"
            + "<if test='creator != null'> and created_by=#{creator}</if></where>";
    // Explicit projection: payloads, results and lease tokens are not list data.
    @Select("<script>select id,task_type,task_domain,status,created_by,space_id,attempt_version,created_at,updated_at,finished_at,archived_at,archived_by from async_task "
            + FILTER + " order by created_at desc,id desc limit #{size} offset #{offset}</script>")
    List<Map<String,Object>> page(@Param("archived") boolean archived, @Param("status") String status,
        @Param("type") String type, @Param("creator") Long creator, @Param("size") int size, @Param("offset") long offset);

    @Select("<script>select count(*) from async_task " + FILTER + "</script>")
    long count(@Param("archived") boolean archived, @Param("status") String status,
        @Param("type") String type, @Param("creator") Long creator);

    @Update("update async_task set archived_at=current_timestamp,archived_by=#{operator},expire_at=null,updated_at=current_timestamp,row_version=row_version+1 "
            + "where id=#{id} and archived_at is null and status in ('SUCCESS','FAILED','CANCELED','SKIPPED_STALE')")
    int archive(@Param("id") Long id, @Param("operator") Long operator);
}
