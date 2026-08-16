package com.ylcloud.mapper;

import com.ylcloud.entity.AdminResourceGrant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AdminResourceGrantMapper {
    @Insert("insert into admin_resource_grant(grantor_id, admin_user_id, resource_type, resource_id, action, status) " +
            "values(#{grantorId}, #{adminUserId}, #{resourceType}, #{resourceId}, #{action}, 1) " +
            "on duplicate key update grantor_id = values(grantor_id), status = 1, update_time = now()")
    int activate(@Param("grantorId") Long grantorId,
                 @Param("adminUserId") Long adminUserId,
                 @Param("resourceType") String resourceType,
                 @Param("resourceId") Long resourceId,
                 @Param("action") String action);

    @Select("select grant_id as id, grantor_id as grantorId, admin_user_id as adminUserId, " +
            "resource_type as resourceType, resource_id as resourceId, action, status, " +
            "create_time as createTime, update_time as updateTime " +
            "from admin_resource_grant where grant_id = #{grantId} for update")
    AdminResourceGrant lockById(@Param("grantId") Long grantId);

    @Update("update admin_resource_grant set status = 0, update_time = now() " +
            "where grant_id = #{grantId} and status = 1")
    int revoke(@Param("grantId") Long grantId);

    @Select("select count(1) from admin_resource_grant " +
            "where admin_user_id = #{adminUserId} and resource_type = #{resourceType} " +
            "and resource_id = #{resourceId} and action = #{action} and status = 1")
    int countActive(@Param("adminUserId") Long adminUserId,
                    @Param("resourceType") String resourceType,
                    @Param("resourceId") Long resourceId,
                    @Param("action") String action);

    @Select("select grant_id as id, grantor_id as grantorId, admin_user_id as adminUserId, " +
            "resource_type as resourceType, resource_id as resourceId, action, status, " +
            "create_time as createTime, update_time as updateTime " +
            "from admin_resource_grant where grantor_id = #{grantorId} and status = 1 " +
            "order by update_time desc, grant_id desc")
    List<AdminResourceGrant> listGrantedBy(@Param("grantorId") Long grantorId);

    @Select("select grant_id as id, grantor_id as grantorId, admin_user_id as adminUserId, " +
            "resource_type as resourceType, resource_id as resourceId, action, status, " +
            "create_time as createTime, update_time as updateTime " +
            "from admin_resource_grant where admin_user_id = #{adminUserId} and status = 1 " +
            "order by update_time desc, grant_id desc")
    List<AdminResourceGrant> listReceivedBy(@Param("adminUserId") Long adminUserId);

    @Select("select grant_id as id, grantor_id as grantorId, admin_user_id as adminUserId, " +
            "resource_type as resourceType, resource_id as resourceId, action, status, " +
            "create_time as createTime, update_time as updateTime " +
            "from admin_resource_grant where grantor_id = #{grantorId} " +
            "and admin_user_id = #{adminUserId} and resource_type = #{resourceType} " +
            "and resource_id = #{resourceId} and status = 1 order by action")
    List<AdminResourceGrant> listScope(@Param("grantorId") Long grantorId,
                                      @Param("adminUserId") Long adminUserId,
                                      @Param("resourceType") String resourceType,
                                      @Param("resourceId") Long resourceId);
}
