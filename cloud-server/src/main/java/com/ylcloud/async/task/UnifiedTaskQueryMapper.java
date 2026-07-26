package com.ylcloud.async.task;

import com.ylcloud.VO.AsyncTaskVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UnifiedTaskQueryMapper {
    String MERGED = """
        select * from (
          select concat('unified-',t.id) id,t.id taskId,t.space_id spaceId,null documentId,
                 'unified' source,t.task_type type,t.task_type title,t.status status,t.status phase,
                 case when t.status='SUCCESS' then 100 when t.status='RUNNING' then 50 else 0 end progress,
                 null total,null current,coalesce(t.last_error_message,t.status) message,
                 t.last_error_message errorMessage,(t.status='FAILED') retryable,
                 t.created_at createTime,t.updated_at updateTime,t.task_domain taskDomain
          from async_task t
          where (t.created_by=#{userId} or exists(
              select 1 from space_member sm where sm.space_id=t.space_id and sm.user_id=#{userId} and sm.status=1
          ))
          union all
          select concat('rag-',r.id),r.id,r.space_id,r.document_id,'rag',r.task_type,
                 'RAG 索引',r.task_status,r.task_status,
                 case when r.task_status='SUCCESS' then 100 when r.task_status='RUNNING' then 50 else 0 end,
                 r.total_count,coalesce(r.success_count,0)+coalesce(r.failed_count,0),
                 coalesce(r.error_message,r.task_status),r.error_message,(r.task_status='FAILED'),
                 r.createtime,r.updatetime,'rag'
          from space_rag_task r where r.created_by=#{userId} and (#{spaceId} is null or r.space_id=#{spaceId})
          union all
          select concat('knowledge-',k.id),k.id,k.space_id,k.document_id,'knowledge',k.task_type,
                 '知识流水线',k.task_status,coalesce(k.terminal_stage,k.stage,k.task_status),
                 coalesce(k.progress,case when k.task_status='SUCCESS' then 100 else 0 end),
                 k.total_count,coalesce(k.success_count,0)+coalesce(k.failed_count,0),
                 coalesce(k.error_message,k.terminal_reason,k.task_status),k.error_message,
                 (k.task_status in ('FAILED','PARTIAL_SUCCESS')),k.createtime,k.updatetime,'knowledge'
          from space_knowledge_pipeline_task k where k.created_by=#{userId} and (#{spaceId} is null or k.space_id=#{spaceId})
        ) merged
        where (#{spaceId} is null or merged.spaceId=#{spaceId})
          and (#{status} is null or merged.status=#{status})
          and (#{domain} is null or merged.taskDomain=#{domain})
          and (#{type} is null or merged.type=#{type})
        """;

    @Select(MERGED + " order by createTime desc,source,id desc limit #{limit} offset #{offset}")
    List<AsyncTaskVO> listMerged(@Param("userId") Long userId,@Param("spaceId") Long spaceId,
                                 @Param("status") String status,@Param("domain") String domain,
                                 @Param("type") String type,@Param("limit") int limit,@Param("offset") int offset);

    @Select("select count(*) from (" + MERGED + ") counted")
    long countMerged(@Param("userId") Long userId,@Param("spaceId") Long spaceId,
                     @Param("status") String status,@Param("domain") String domain,@Param("type") String type);
}
