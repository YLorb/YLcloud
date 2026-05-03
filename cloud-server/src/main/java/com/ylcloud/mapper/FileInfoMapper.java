package com.ylcloud.mapper;

import com.ylcloud.entity.File;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FileInfoMapper {

    @Select("select * from file_info where md5 = #{md5}")
    File getByMD5(@Param("md5") String md5);

    @Select("select * from file_info where file_uuid = #{fileUuid}")
    File getByFileUuid(@Param("fileUuid") String fileUuid);
}
