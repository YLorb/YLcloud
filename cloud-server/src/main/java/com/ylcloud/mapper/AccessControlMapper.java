package com.ylcloud.mapper;

import com.ylcloud.entity.PermissionGrant;
import com.ylcloud.entity.PermissionGroup;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AccessControlMapper {
    @Select("select g.group_id as id, g.group_name as name, g.description, g.system_group as systemGroup, " +
            "g.create_time as createTime, g.update_time as updateTime, count(upg.user_id) as userCount " +
            "from permission_group g left join user_permission_group upg on upg.group_id = g.group_id " +
            "group by g.group_id order by g.system_group desc, g.group_name asc")
    List<PermissionGroup> listGroups();

    @Select("select group_id as id, group_name as name, description, system_group as systemGroup, " +
            "create_time as createTime, update_time as updateTime from permission_group where group_id = #{groupId}")
    PermissionGroup getGroup(@Param("groupId") Long groupId);

    @Select("select group_id as id, group_name as name, description, system_group as systemGroup, " +
            "create_time as createTime, update_time as updateTime from permission_group where group_id = #{groupId} for update")
    PermissionGroup lockGroup(@Param("groupId") Long groupId);

    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "group_id")
    @Insert("insert into permission_group(group_name, description, system_group, created_by) values(#{name}, #{description}, 0, #{createdBy})")
    int insertGroup(PermissionGroup group);

    @Update("update permission_group set group_name = #{name}, description = #{description}, update_time = now() where group_id = #{groupId}")
    int updateGroup(@Param("groupId") Long groupId, @Param("name") String name, @Param("description") String description);

    @Delete("delete from permission_group where group_id = #{groupId}")
    int deleteGroup(@Param("groupId") Long groupId);

    @Select("select count(1) from user_permission_group where group_id = #{groupId}")
    int countGroupMembers(@Param("groupId") Long groupId);

    @Select("select group_id as subjectId, permission_key as permissionKey, allowed from permission_group_grant order by group_id, permission_key")
    List<PermissionGrant> listAllGroupGrants();

    @Delete("delete from permission_group_grant where group_id = #{groupId}")
    int deleteGroupGrants(@Param("groupId") Long groupId);

    @Insert("insert into permission_group_grant(group_id, permission_key, allowed) values(#{groupId}, #{permissionKey}, #{allowed})")
    int insertGroupGrant(@Param("groupId") Long groupId, @Param("permissionKey") String permissionKey,
                         @Param("allowed") boolean allowed);

    @Select("select group_id from user_permission_group where user_id = #{userId}")
    Long getUserGroupId(@Param("userId") Long userId);

    @Insert("insert into user_permission_group(user_id, group_id) values(#{userId}, #{groupId}) " +
            "on duplicate key update group_id = values(group_id), update_time = now()")
    int assignUserGroup(@Param("userId") Long userId, @Param("groupId") Long groupId);

    @Delete("delete from user_permission_group where user_id = #{userId}")
    int clearUserGroup(@Param("userId") Long userId);

    @Select("select user_id as subjectId, permission_key as permissionKey, allowed from user_permission_override where user_id = #{userId}")
    List<PermissionGrant> listUserOverrides(@Param("userId") Long userId);

    @Delete("delete from user_permission_override where user_id = #{userId}")
    int deleteUserOverrides(@Param("userId") Long userId);

    @Insert("insert into user_permission_override(user_id, permission_key, allowed) values(#{userId}, #{permissionKey}, #{allowed})")
    int insertUserOverride(@Param("userId") Long userId, @Param("permissionKey") String permissionKey,
                           @Param("allowed") boolean allowed);

    @Select("select case when upper(u.role) = 'ADMIN' then 1 else coalesce(uo.allowed, gg.allowed, 1) end " +
            "from users u left join user_permission_group ug on ug.user_id = u.user_id " +
            "left join permission_group_grant gg on gg.group_id = ug.group_id and gg.permission_key = #{permissionKey} " +
            "left join user_permission_override uo on uo.user_id = u.user_id and uo.permission_key = #{permissionKey} " +
            "where u.user_id = #{userId} and u.status = 1")
    Integer resolvePermission(@Param("userId") Long userId, @Param("permissionKey") String permissionKey);

    @Insert("insert into permission_audit_log(operator_id, target_type, target_id, action, before_json, after_json) " +
            "values(#{operatorId}, #{targetType}, #{targetId}, #{action}, #{beforeJson}, #{afterJson})")
    int insertAudit(@Param("operatorId") Long operatorId, @Param("targetType") String targetType,
                    @Param("targetId") Long targetId, @Param("action") String action,
                    @Param("beforeJson") String beforeJson, @Param("afterJson") String afterJson);
}
