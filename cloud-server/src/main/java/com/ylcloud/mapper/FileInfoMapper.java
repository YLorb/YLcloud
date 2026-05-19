package com.ylcloud.mapper;

import com.ylcloud.VO.FileVO;
import com.ylcloud.entity.File;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FileInfoMapper {

    @Select("select * from file_info where file_id = #{file_id} and user_id = #{userId}")
    File getByFileId(Long file_id,Long userId);

    @Select("select * from file_info where file_uuid = #{fileUuid} and user_id = #{userId}")
    File getByFileUuid(@Param("fileUuid") String fileUuid,@Param("userId") Long userId);

    @Select("select * from file_info where hash = #{hash} and parent_id = #{parentId} and user_id = #{userId}")
    File getByHash(@Param("hash") String hash,@Param("parentId") Long parentId,@Param("userId") Long userId);

    @Select("select * from file_info where user_id = #{userId}")
    List<FileVO> listFileByUserId(Long userId);

    @Select("select * from file_info " +
            "where user_id = #{userId} " +
            "and parent_id = 0 " +
            "and name = '/' " +
            "and is_dir = 1 " +
            "and status = 1 " +
            "limit 1")
    File getRootDirByUserId(@Param("userId") Long userId);

    @Select("select file_id, file_uuid, is_dir, user_id, parent_id, name, type, size, hash, createtime, updatetime " +
            "from file_info " +
            "where user_id = #{userId} " +
            "and parent_id = #{parentId} " +
            "and status = 1 " +
            "order by is_dir desc, updatetime desc")
    List<FileVO> listFileVOByparentId(@Param("parentId") Long parentId,
                                      @Param("userId") Long userId);

    @Select("select * from file_info " +
            "where user_id = #{userId} " +
            "and parent_id = #{parentId} " +
            "and status = 1 " +
            "order by is_dir desc, updatetime desc")
    List<File> listFileByparentId(@Param("parentId") Long parentId,
                                  @Param("userId") Long userId);

    @Options(useGeneratedKeys = true,keyProperty = "fileId",keyColumn = "file_id")
    @Insert("insert into file_info(file_uuid,is_dir,user_id,parent_id,name,type,size,path,md5,hash,status,createtime,updatetime)" +
            "values " +
            "(#{fileUuid},#{isDir},#{userId},#{parentId},#{name},#{type},#{size},#{path},#{md5},#{hash},#{status},#{createTime},#{updateTime})")
    int insertFileInfo(File file);

    @Update("update file_info " +
            "set name = #{newName}, " +
            "updatetime = #{updateTime} " +
            "where file_uuid = #{fileUuid} " +
            "and user_id = #{userId} " +
            "and status = 1")
    int updateName(String fileUuid, String newName, Long userId, LocalDateTime updateTime);

    @Delete("delete from file_info where file_uuid = #{fileUuid} and user_id = #{userId}")
    int deleteByfileUuid(String fileUuid,Long userId);

    @Select("select * from file_info " +
            "where name = #{name} " +
            "and user_id = #{userId} " +
            "and parent_id = #{parentId} " +
            "and status = 1 " +
            "limit 1")
    File findFileByName(@Param("name") String name,
                        @Param("userId") Long userId,
                        @Param("parentId") Long parentId);

    @Select("select count(1) > 0 from file_info " +
            "where file_id = #{parentId} " +
            "and user_id = #{userId} " +
            "and is_dir = 1 " +
            "and status = 1 ")
    boolean ParentIdExist(Long parentId,Long userId);

    @Update("update file_info " +
            "set parent_id = #{NewParentId}, " +
            "updatetime = #{timenow} " +
            "where file_id = #{fileId}")
    int updateParent(Long fileId, Long NewParentId, LocalDateTime timenow);
}
