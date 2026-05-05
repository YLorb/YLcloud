package com.ylcloud.mapper;

import com.ylcloud.DTO.FileDTO;
import com.ylcloud.VO.FileVO;
import com.ylcloud.entity.File;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FileInfoMapper {

    @Select("select * from file_info where md5 = #{md5}")
    File getByMD5(@Param("md5") String md5);

    @Select("select * from file_info where file_uuid = #{fileUuid}")
    File getByFileUuid(@Param("fileUuid") String fileUuid);

    @Select("select * from file_info where hash = #{hash}")
    File getByHash(@Param("hash") String hash);

    @Select("select file_id,name,size,type,updatetime from file_info where user_id = #{userId}")
    List<FileVO> listFileByUserId(Long userId);

    @Insert("insert into file_info(file_uuid,is_dir,user_id,parent_id,name,type,size,path,md5,hash,status,createtime,updatetime)" +
            "values " +
            "(#{fileUuid},#{isDir},#{userId},#{parentId},#{name},#{type},#{size},#{path},#{md5},#{hash},#{status},#{createTime},#{updateTime})")
    void insertFileInfo(FileDTO fileDTO);
}
