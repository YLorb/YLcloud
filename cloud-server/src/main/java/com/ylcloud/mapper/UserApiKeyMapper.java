package com.ylcloud.mapper;

import com.ylcloud.entity.UserApiKey;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Mapper
public interface UserApiKeyMapper {
    String COLUMNS = "key_id as id,user_id as userId,key_name as keyName,key_prefix as keyPrefix," +
            "key_hash as keyHash,drive_access as driveAccess,drive_root_file_id as driveRootFileId," +
            "key_status as keyStatus,expires_at as expiresAt,last_used_at as lastUsedAt," +
            "revoked_at as revokedAt,createtime as createTime,updatetime as updateTime";

    @Insert("insert into user_api_key(user_id,key_name,key_prefix,key_hash,drive_access,drive_root_file_id," +
            "key_status,expires_at,createtime,updatetime) values(#{userId},#{keyName},#{keyPrefix},#{keyHash}," +
            "#{driveAccess},#{driveRootFileId},#{keyStatus},#{expiresAt},#{createTime},#{updateTime})")
    @Options(useGeneratedKeys = true,keyProperty = "id",keyColumn = "key_id")
    int insert(UserApiKey apiKey);

    @Select("select " + COLUMNS + " from user_api_key where key_prefix=#{prefix} limit 1")
    UserApiKey getByPrefix(@Param("prefix") String prefix);

    @Select("select " + COLUMNS + " from user_api_key where key_id=#{keyId} for update")
    UserApiKey lockById(@Param("keyId") Long keyId);

    @Select("select " + COLUMNS + " from user_api_key where user_id=#{userId} " +
            "order by createtime desc,key_id desc")
    List<UserApiKey> listByUser(@Param("userId") Long userId);

    @Select("select count(1) from user_api_key where user_id=#{userId} and key_status='ACTIVE' " +
            "and (expires_at is null or expires_at>now())")
    int countActiveByUser(@Param("userId") Long userId);

    @Update("update user_api_key set key_status='REVOKED',revoked_at=#{now},updatetime=#{now} " +
            "where key_id=#{keyId} and key_status='ACTIVE'")
    int revoke(@Param("keyId") Long keyId,@Param("now") LocalDateTime now);

    @Update("update user_api_key set last_used_at=#{now},updatetime=#{now} " +
            "where key_id=#{keyId} and key_status='ACTIVE' and (expires_at is null or expires_at>#{now})")
    int touch(@Param("keyId") Long keyId,@Param("now") LocalDateTime now);

    @Insert("insert into api_key_scope(key_id,scope_key,createtime) values(#{keyId},#{scope},#{now})")
    int insertScope(@Param("keyId") Long keyId,@Param("scope") String scope,@Param("now") LocalDateTime now);

    @Select("select scope_key from api_key_scope where key_id=#{keyId} order by scope_key")
    Set<String> listScopes(@Param("keyId") Long keyId);

    @Insert("insert into api_key_space_scope(key_id,space_id,createtime) values(#{keyId},#{spaceId},#{now})")
    int insertSpaceScope(@Param("keyId") Long keyId,@Param("spaceId") Long spaceId,@Param("now") LocalDateTime now);

    @Select("select space_id from api_key_space_scope where key_id=#{keyId} order by space_id")
    Set<Long> listSpaceIds(@Param("keyId") Long keyId);

    @Select("select count(1) from api_key_space_scope where key_id=#{keyId} and space_id=#{spaceId}")
    int countSpaceScope(@Param("keyId") Long keyId,@Param("spaceId") Long spaceId);

    @Select("with recursive ancestors as (" +
            "select ID,parent_id,user_id from user_file where ID=#{fileId} and user_id=#{userId} and status=1 " +
            "union all select parent.ID,parent.parent_id,parent.user_id from user_file parent " +
            "join ancestors child on parent.ID=child.parent_id " +
            "where parent.user_id=#{userId} and parent.status=1) " +
            "select count(1) from ancestors where ID=#{rootFileId}")
    int countWithinDriveRoot(@Param("userId") Long userId,@Param("fileId") Long fileId,
                             @Param("rootFileId") Long rootFileId);
}
