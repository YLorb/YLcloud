package com.ylcloud.mapper;

import com.ylcloud.entity.CrossStoreOperation;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CrossStoreOperationMapper {
    String COLUMNS = "id, operation_key as operationKey, operation_type as operationType, " +
            "operation_status as operationStatus, payload_hash as payloadHash, resource_id as resourceId, " +
            "external_ref as externalRef, result_ref as resultRef, attempt_count as attemptCount, lease_until as leaseUntil, " +
            "error_message as errorMessage, createtime, updatetime";

    @Insert("insert ignore into cross_store_operation(operation_key, operation_type, operation_status, payload_hash, resource_id, " +
            "result_ref, attempt_count, lease_until, error_message, createtime, updatetime) " +
            "values(#{operationKey}, #{operationType}, 'PENDING', #{payloadHash}, #{resourceId}, null, 0, null, null, #{now}, #{now})")
    int insertIfAbsent(@Param("operationKey") String operationKey,
                       @Param("operationType") String operationType,
                       @Param("payloadHash") String payloadHash,
                       @Param("resourceId") String resourceId,
                       @Param("now") LocalDateTime now);

    @Select("select " + COLUMNS + " from cross_store_operation where operation_key = #{operationKey}")
    CrossStoreOperation get(@Param("operationKey") String operationKey);

    @Update("update cross_store_operation set operation_status = 'RUNNING', attempt_count = attempt_count + 1, " +
            "lease_until = #{leaseUntil}, error_message = null, updatetime = #{now} " +
            "where operation_key = #{operationKey} and (operation_status in ('PENDING','FAILED') " +
            "or (operation_status = 'RUNNING' and lease_until &lt; #{now}))")
    int claim(@Param("operationKey") String operationKey,
              @Param("leaseUntil") LocalDateTime leaseUntil,
              @Param("now") LocalDateTime now);

    @Update("update cross_store_operation set operation_status = 'SUCCESS', result_ref = #{resultRef}, " +
            "lease_until = null, error_message = null, updatetime = #{now} " +
            "where operation_key = #{operationKey} and operation_status = 'RUNNING'")
    int markSuccess(@Param("operationKey") String operationKey,
                    @Param("resultRef") String resultRef,
                    @Param("now") LocalDateTime now);

    @Update("update cross_store_operation set operation_status = 'FAILED', lease_until = null, " +
            "error_message = #{errorMessage}, updatetime = #{now} " +
            "where operation_key = #{operationKey} and operation_status = 'RUNNING'")
    int markFailed(@Param("operationKey") String operationKey,
                   @Param("errorMessage") String errorMessage,
                   @Param("now") LocalDateTime now);

    @Update("update cross_store_operation set result_ref = #{resultRef}, updatetime = #{now} " +
            "where operation_key = #{operationKey} and operation_status = 'RUNNING'")
    int recordResultCandidate(@Param("operationKey") String operationKey,
                              @Param("resultRef") String resultRef,
                              @Param("now") LocalDateTime now);

    @Update("update cross_store_operation set external_ref = #{externalRef}, updatetime = #{now} " +
            "where operation_key = #{operationKey} and operation_status = 'RUNNING'")
    int recordExternalRef(@Param("operationKey") String operationKey,
                          @Param("externalRef") String externalRef,
                          @Param("now") LocalDateTime now);

    @Select("select " + COLUMNS + " from cross_store_operation where operation_status = 'RUNNING' " +
            "and lease_until &lt; #{now} order by lease_until asc limit #{limit}")
    List<CrossStoreOperation> listStaleRunning(@Param("now") LocalDateTime now, @Param("limit") Integer limit);

    @Select("select " + COLUMNS + " from cross_store_operation where operation_status in ('PENDING','FAILED') " +
            "or (operation_status = 'RUNNING' and lease_until &lt; #{now}) order by updatetime asc limit #{limit}")
    List<CrossStoreOperation> listRetryable(@Param("now") LocalDateTime now, @Param("limit") Integer limit);
}
