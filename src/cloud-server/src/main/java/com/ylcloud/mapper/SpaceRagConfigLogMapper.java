package com.ylcloud.mapper;

import com.ylcloud.entity.SpaceRagConfigLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 空间 RAG 配置变更日志 Mapper。
 */
@Mapper
public interface SpaceRagConfigLogMapper {

    /**
     * 新增 insert 相关逻辑。
     * @return 影响行数
     */
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("insert into space_rag_config_log(space_id, operator_id, changed_fields, before_json, after_json, createtime) " +
            "values(#{spaceId}, #{operatorId}, #{changedFields}, #{beforeJson}, #{afterJson}, #{createtime})")
    int insert(SpaceRagConfigLog log);

    @Select("select id, space_id as spaceId, operator_id as operatorId, changed_fields as changedFields, before_json as beforeJson, after_json as afterJson, createtime " +
            "from space_rag_config_log where space_id = #{spaceId} order by createtime desc limit #{limit}")
    List<SpaceRagConfigLog> listRecent(@Param("spaceId") Long spaceId, @Param("limit") Integer limit);
}
