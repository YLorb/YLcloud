package com.ylcloud.mapper;

import com.ylcloud.entity.SpaceFileDeleteBatch;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;

@Mapper
public interface SpaceFileDeleteBatchMapper {
    @Options(useGeneratedKeys = true,keyProperty = "id")
    @Insert("insert into space_file_delete_batch(batch_key,space_id,root_file_id,root_node_version,subtree_digest,folder_count,file_count,knowledge_count,batch_status,processed_count,created_by,createtime,updatetime) " +
            "values(#{batchKey},#{spaceId},#{rootFileId},#{rootNodeVersion},#{subtreeDigest},#{folderCount},#{fileCount},#{knowledgeCount},#{batchStatus},0,#{createdBy},#{createtime},#{updatetime})")
    int insert(SpaceFileDeleteBatch batch);

    @Select("select id,batch_key as batchKey,space_id as spaceId,root_file_id as rootFileId,root_node_version as rootNodeVersion,subtree_digest as subtreeDigest,folder_count as folderCount,file_count as fileCount,knowledge_count as knowledgeCount,batch_status as batchStatus,processed_count as processedCount,error_message as errorMessage,async_task_id as asyncTaskId,created_by as createdBy,createtime,updatetime from space_file_delete_batch where id=#{id}")
    SpaceFileDeleteBatch getById(@Param("id") Long id);

    @Update("update space_file_delete_batch set async_task_id=#{taskId},updatetime=#{now} where id=#{id}")
    int bindTask(@Param("id") Long id,@Param("taskId") Long taskId,@Param("now") LocalDateTime now);

    @Update("update space_file_delete_batch set batch_status=#{status},processed_count=#{processed},error_message=#{error},updatetime=#{now} where id=#{id}")
    int finish(@Param("id") Long id,@Param("status") String status,@Param("processed") Integer processed,
               @Param("error") String error,@Param("now") LocalDateTime now);
}
