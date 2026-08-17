package com.ylcloud.mapper;

import com.ylcloud.entity.SpaceFileImportBatch;
import com.ylcloud.entity.SpaceFileImportItem;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SpaceFileImportBatchMapper {
    @Options(useGeneratedKeys=true,keyProperty="id")
    @Insert("insert into space_file_import_batch(batch_key,space_id,target_parent_id,failure_policy,batch_status,total_count,passed_count,failed_count,imported_count,created_by,resource_version,createtime,updatetime) values(#{batchKey},#{spaceId},#{targetParentId},#{failurePolicy},#{batchStatus},#{totalCount},0,0,0,#{createdBy},1,#{createtime},#{updatetime})")
    int insertBatch(SpaceFileImportBatch batch);

    @Options(useGeneratedKeys=true,keyProperty="id")
    @Insert("insert into space_file_import_item(batch_id,source_user_file_id,source_type,relative_path,file_uuid,content_hash,file_size,item_status,createtime,updatetime) values(#{batchId},#{sourceUserFileId},#{sourceType},#{relativePath},#{fileUuid},#{contentHash},#{fileSize},#{itemStatus},#{createtime},#{updatetime})")
    int insertItem(SpaceFileImportItem item);

    @Update("update space_file_import_item set item_status=#{status},error_code=#{code},error_message=#{message},space_file_id=#{spaceFileId},sandbox_invocation_id=#{invocationId},updatetime=#{now} where id=#{id}")
    int updateItem(@Param("id") Long id,@Param("status") String status,@Param("code") String code,
                   @Param("message") String message,@Param("spaceFileId") Long spaceFileId,
                   @Param("invocationId") String invocationId,@Param("now") LocalDateTime now);

    @Update("update space_file_import_batch set batch_status=#{status},passed_count=#{passed},failed_count=#{failed},imported_count=#{imported},error_message=#{error},resource_version=resource_version+1,updatetime=#{now} where id=#{id}")
    int finishBatch(@Param("id") Long id,@Param("status") String status,@Param("passed") Integer passed,
                    @Param("failed") Integer failed,@Param("imported") Integer imported,
                    @Param("error") String error,@Param("now") LocalDateTime now);

    @Update("update space_file_import_batch set async_task_id=#{taskId},updatetime=#{now} where id=#{id}")
    int bindTask(@Param("id") Long id,@Param("taskId") Long taskId,@Param("now") LocalDateTime now);

    @Select("select id,batch_key as batchKey,space_id as spaceId,target_parent_id as targetParentId,failure_policy as failurePolicy,batch_status as batchStatus,total_count as totalCount,passed_count as passedCount,failed_count as failedCount,imported_count as importedCount,error_message as errorMessage,async_task_id as asyncTaskId,created_by as createdBy,resource_version as resourceVersion,createtime,updatetime from space_file_import_batch where id=#{id}")
    SpaceFileImportBatch getBatch(@Param("id") Long id);

    @Select("select id,batch_id as batchId,source_user_file_id as sourceUserFileId,source_type as sourceType,relative_path as relativePath,file_uuid as fileUuid,content_hash as contentHash,file_size as fileSize,item_status as itemStatus,error_code as errorCode,error_message as errorMessage,space_file_id as spaceFileId,sandbox_invocation_id as sandboxInvocationId,createtime,updatetime from space_file_import_item where batch_id=#{batchId} order by id")
    List<SpaceFileImportItem> listItems(@Param("batchId") Long batchId);
}
