package com.ylcloud.mapper;

import com.ylcloud.entity.FileShare;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface FileShareMapper {

    @Select("select id, share_code as shareCode, user_file_id as userFileId, file_uuid as fileUuid, " +
            "owner_id as ownerId, status, createtime as createTime, updatetime as updateTime " +
            "from file_share " +
            "where user_file_id = #{userFileId} " +
            "and owner_id = #{ownerId} " +
            "and status = 1 " +
            "limit 1")
    FileShare getActiveByUserFileId(@Param("userFileId") Long userFileId, @Param("ownerId") Long ownerId);

    @Select("select id, share_code as shareCode, user_file_id as userFileId, file_uuid as fileUuid, " +
            "owner_id as ownerId, status, createtime as createTime, updatetime as updateTime " +
            "from file_share " +
            "where share_code = #{shareCode} " +
            "and status = 1 " +
            "limit 1")
    FileShare getActiveByShareCode(@Param("shareCode") String shareCode);

    @Select("select count(1) > 0 " +
            "from file_share " +
            "where share_code = #{shareCode}")
    boolean existsByShareCode(@Param("shareCode") String shareCode);

    @Insert("insert into file_share(share_code, user_file_id, file_uuid, owner_id, status, createtime, updatetime) " +
            "values(#{shareCode}, #{userFileId}, #{fileUuid}, #{ownerId}, #{status}, #{createTime}, #{updateTime})")
    int insert(FileShare fileShare);

    @Update("update file_share " +
            "set status = 0, updatetime = now() " +
            "where user_file_id = #{userFileId} " +
            "and owner_id = #{ownerId} " +
            "and status = 1")
    int disableByUserFileId(@Param("userFileId") Long userFileId, @Param("ownerId") Long ownerId);
}
