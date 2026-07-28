package com.ylcloud.mapper;

import com.ylcloud.VO.SpaceVO;
import com.ylcloud.entity.Space;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 空间表 Mapper。
 */
@Mapper
public interface SpaceMapper {

    /**
     * 新增 insert 相关逻辑。
     * @return 影响行数
     */
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("insert into spaces(name, description, type, lifecycle_state, owner_id, root_dir_id, rag_status, version_enabled, status, createtime, updatetime) " +
            "values(#{name}, #{description}, #{type}, #{lifecycleState}, #{ownerId}, #{rootDirId}, #{ragStatus}, #{versionEnabled}, #{status}, #{createtime}, #{updatetime})")
    int insert(Space space);

    /**
     * 查询 getById 相关逻辑。
     * @return 处理结果
     */
    @Select("select id, name, description, type, lifecycle_state as lifecycleState, owner_id as ownerId, root_dir_id as rootDirId, " +
            "rag_status as ragStatus, version_enabled as versionEnabled, status, createtime, updatetime " +
            "from spaces where id = #{spaceId} and status = 1 and lifecycle_state = 'ACTIVE'")
    Space getById(@Param("spaceId") Long spaceId);

    @Select("select id, name, description, type, lifecycle_state as lifecycleState, owner_id as ownerId, root_dir_id as rootDirId, " +
            "rag_status as ragStatus, version_enabled as versionEnabled, status, createtime, updatetime " +
            "from spaces where id = #{spaceId} and status = 1 for update")
    Space getByIdForUpdate(@Param("spaceId") Long spaceId);

    @Select("select id, name, description, type, lifecycle_state as lifecycleState, owner_id as ownerId, root_dir_id as rootDirId, " +
            "rag_status as ragStatus, version_enabled as versionEnabled, status, createtime, updatetime " +
            "from spaces where owner_id = #{userId} and type = 'PERSONAL' and status = 1 and lifecycle_state = 'ACTIVE' limit 1")
    Space getActivePersonalByOwnerId(@Param("userId") Long userId);

    /**
     * 查询 listByUserId 相关逻辑。
     * @return 列表结果
     */
    @Select("select s.id, s.name, s.description, s.type, s.lifecycle_state as lifecycleState, s.owner_id as ownerId, s.root_dir_id as rootDirId, " +
            "m.role, s.rag_status as ragStatus, s.version_enabled as versionEnabled, s.createtime, s.updatetime " +
            "from spaces s join space_member m on s.id = m.space_id " +
            "where m.user_id = #{userId} and m.status = 1 and s.status = 1 and s.lifecycle_state = 'ACTIVE' " +
            "order by s.updatetime desc")
    List<SpaceVO> listByUserId(@Param("userId") Long userId);

    /**
     * 更新 updateRootDir 相关逻辑。
     * @return 影响行数
     */
    @Update("update spaces set root_dir_id = #{rootDirId}, updatetime = #{updateTime} where id = #{spaceId}")
    int updateRootDir(@Param("spaceId") Long spaceId,
                      @Param("rootDirId") Long rootDirId,
                      @Param("updateTime") LocalDateTime updateTime);

    /**
     * 更新 updateInfo 相关逻辑。
     * @return 影响行数
     */
    @Update("update spaces set name = #{name}, description = #{description}, updatetime = #{updateTime} " +
            "where id = #{spaceId} and status = 1")
    int updateInfo(@Param("spaceId") Long spaceId,
                   @Param("name") String name,
                   @Param("description") String description,
                   @Param("updateTime") LocalDateTime updateTime);

    /**
     * 更新 updateVersionEnabled 相关逻辑。
     * @return 影响行数
     */
    @Update("update spaces set version_enabled = #{versionEnabled}, updatetime = #{updateTime} where id = #{spaceId} and status = 1")
    int updateVersionEnabled(@Param("spaceId") Long spaceId,
                             @Param("versionEnabled") Integer versionEnabled,
                             @Param("updateTime") LocalDateTime updateTime);

    @Update("update spaces set owner_id = #{ownerId}, updatetime = #{updateTime} " +
            "where id = #{spaceId} and status = 1 and lifecycle_state = 'ACTIVE'")
    int updateOwner(@Param("spaceId") Long spaceId,
                    @Param("ownerId") Long ownerId,
                    @Param("updateTime") LocalDateTime updateTime);

    @Update("update spaces set lifecycle_state = 'DISSOLVING', updatetime = #{updateTime} " +
            "where id = #{spaceId} and status = 1 and lifecycle_state = 'ACTIVE'")
    int markDissolving(@Param("spaceId") Long spaceId,
                       @Param("updateTime") LocalDateTime updateTime);

    /**
     * 执行 disable 函数的业务处理。
     * @return 影响行数
     */
    @Update("update spaces set status = 0, updatetime = #{updateTime} where id = #{spaceId} and status = 1")
    int disable(@Param("spaceId") Long spaceId, @Param("updateTime") LocalDateTime updateTime);

    @Update("update spaces set name = concat('deleted-personal-space-', id), description = null, " +
            "lifecycle_state = 'DISSOLVED', rag_status = 0, status = 0, updatetime = #{now} " +
            "where id = #{spaceId} and owner_id = #{userId} and type = 'PERSONAL'")
    int purgePersonal(@Param("spaceId") Long spaceId, @Param("userId") Long userId,
                      @Param("now") LocalDateTime now);
}
