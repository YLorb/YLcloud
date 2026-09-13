package com.ylcloud.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;
import java.util.Map;

@Mapper
public interface AdminMetricsMapper {
    // TIMESTAMP -> epoch is independent of the JDBC session zone. Shanghai is UTC+08.
    @Select("select floor((unix_timestamp(create_time)+28800)/86400) as day, count(*) as total from users "
            + "where create_time >= from_unixtime(#{start}) and create_time < from_unixtime(#{end}) group by day")
    List<Map<String,Object>> users(@Param("start") long start, @Param("end") long end);

    @Select("select floor((unix_timestamp(createtime)+28800)/86400) as day, count(*) as total from file_info "
            + "where createtime >= from_unixtime(#{start}) and createtime < from_unixtime(#{end}) group by day")
    List<Map<String,Object>> blobs(@Param("start") long start, @Param("end") long end);

    @Select("select floor((unix_timestamp(createtime)+28800)/86400) as day, count(*) as total from spaces "
            + "where createtime >= from_unixtime(#{start}) and createtime < from_unixtime(#{end}) group by day")
    List<Map<String,Object>> spaces(@Param("start") long start, @Param("end") long end);

    @Select("select day,sum(total) as total from ("
            + "select floor((unix_timestamp(createtime)+28800)/86400) as day,count(*) as total from user_file "
            + "where is_dir=0 and createtime >= from_unixtime(#{start}) and createtime < from_unixtime(#{end}) group by day "
            + "union all select floor((unix_timestamp(createtime)+28800)/86400) as day,count(*) as total from space_file "
            + "where is_dir=0 and createtime >= from_unixtime(#{start}) and createtime < from_unixtime(#{end}) group by day"
            + ") refs group by day")
    List<Map<String,Object>> references(@Param("start") long start, @Param("end") long end);
}
