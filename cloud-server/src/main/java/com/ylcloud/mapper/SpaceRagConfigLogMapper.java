package com.ylcloud.mapper;

import com.ylcloud.entity.SpaceRagConfigLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;

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
}
