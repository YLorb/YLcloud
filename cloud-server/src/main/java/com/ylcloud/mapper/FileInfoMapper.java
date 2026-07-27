package com.ylcloud.mapper;

import com.ylcloud.DTO.UserFileDTO;
import com.ylcloud.VO.FileVO;
import com.ylcloud.entity.File;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FileInfoMapper {

    /**
     * 查询 getByFileId 相关逻辑。
     * @return 处理结果
     */
    @Select("select ID as id, file_name as fileName, file_uuid as fileUuid, is_dir as dir, " +
            "status, user_id as userId, parent_id as parentId, path, createtime, updatetime " +
            "from user_file " +
            "where ID = #{fileId} " +
            "and user_id = #{userId} " +
            "and status = 1")
    UserFileDTO getByFileId(@Param("fileId") Long fileId, @Param("userId") Long userId);

    /**
     * 查询 getUserFileByPhysicalFileId 相关逻辑。
     * @return 处理结果
     */
    @Select("select uf.ID as id, uf.file_name as fileName, uf.file_uuid as fileUuid, uf.is_dir as dir, " +
            "uf.status, uf.user_id as userId, uf.parent_id as parentId, uf.path, uf.createtime, uf.updatetime " +
            "from user_file uf " +
            "join file_info fi on fi.file_uuid = uf.file_uuid " +
            "where fi.file_id = #{fileId} " +
            "and uf.user_id = #{userId} " +
            "and uf.status = 1 " +
            "and fi.status = 1 " +
            "order by uf.updatetime desc " +
            "limit 1")
    UserFileDTO getUserFileByPhysicalFileId(@Param("fileId") Long fileId, @Param("userId") Long userId);

    /**
     * 查询 getByFileIdActiveOrRecycle 相关逻辑。
     * @return 处理结果
     */
    @Select("select ID as id, file_name as fileName, file_uuid as fileUuid, is_dir as dir, " +
            "status, user_id as userId, parent_id as parentId, path, createtime, updatetime " +
            "from user_file " +
            "where ID = #{fileId} " +
            "and user_id = #{userId} " +
            "and status in (1, 2)")
    UserFileDTO getByFileIdActiveOrRecycle(@Param("fileId") Long fileId, @Param("userId") Long userId);

    /**
     * 查询 getByFileUuid 相关逻辑。
     * @return 处理结果
     */
    @Select("select ID as id, file_name as fileName, file_uuid as fileUuid, is_dir as dir, " +
            "status, user_id as userId, parent_id as parentId, path, createtime, updatetime " +
            "from user_file " +
            "where file_uuid = #{fileUuid} " +
            "and user_id = #{userId} " +
            "and status = 1")
    UserFileDTO getByFileUuid(@Param("fileUuid") String fileUuid, @Param("userId") Long userId);


    /**
     * 查询 getByFileUuid 相关逻辑。
     * @return 处理结果
     */
    @Select("select ID as id, file_name as fileName, file_uuid as fileUuid, is_dir as dir, " +
            "status, user_id as userId, parent_id as parentId, path, createtime, updatetime " +
            "from user_file " +
            "where file_uuid = #{fileUuid} " +
            "and user_id = #{userId} " +
            "and parent_id = #{parentId} " +
            "and status = 1")
    UserFileDTO getByFileUuidAndParent(@Param("fileUuid") String fileUuid, @Param("parentId") Long parentId,@Param("userId") Long userId);

    /**
     * 查询 getByFileIdAny 相关逻辑。
     * @return 处理结果
     */
    @Select("select ID as id, file_name as fileName, file_uuid as fileUuid, is_dir as dir, " +
            "status, user_id as userId, parent_id as parentId, path, createtime, updatetime " +
            "from user_file " +
            "where ID = #{fileId} " +
            "and status = 1")
    UserFileDTO getByFileIdAny(@Param("fileId") Long fileId);

    @Select("select ID as id, file_name as fileName, file_uuid as fileUuid, is_dir as dir, status, " +
            "user_id as userId, parent_id as parentId, path, createtime, updatetime " +
            "from user_file where ID = #{fileId} for update")
    UserFileDTO lockUserFileById(@Param("fileId") Long fileId);

    /**
     * 查询 getByFileIdAnyActiveOrRecycle 相关逻辑。
     * @return 处理结果
     */
    @Select("select ID as id, file_name as fileName, file_uuid as fileUuid, is_dir as dir, " +
            "status, user_id as userId, parent_id as parentId, path, createtime, updatetime " +
            "from user_file " +
            "where ID = #{fileId} " +
            "and status in (1, 2)")
    UserFileDTO getByFileIdAnyActiveOrRecycle(@Param("fileId") Long fileId);

    /**
     * 查询 getByFileUuidAny 相关逻辑。
     * @return 处理结果
     */
    @Select("select ID as id, file_name as fileName, file_uuid as fileUuid, is_dir as dir, " +
            "status, user_id as userId, parent_id as parentId, path, createtime, updatetime " +
            "from user_file " +
            "where file_uuid = #{fileUuid} " +
            "and parent_id = #{parentId} " +
            "and status = 1 " +
            "limit 1")
    UserFileDTO getByFileUuidAny(@Param("fileUuid") String fileUuid, @Param("parentId") Long parentId);

    /**
     * 查询 getFileInfo 相关逻辑。
     * @return 处理结果
     */
    @Select("select file_id as fileId, file_uuid as fileUuid, " +
            "name, type, size, md5, sha1, hash, status, " +
            "createtime as createTime, updatetime as updateTime " +
            "from file_info " +
            "where file_uuid = #{fileUuid} " +
            "and status = 1 " +
            "limit 1")
    File getFileInfo(@Param("fileUuid") String fileUuid, @Param("userId") Long userId);

    /**
     * 查询 getFileByFileUuid 相关逻辑。
     * @return 处理结果
     */
    @Select("select file_id as fileId, file_uuid as fileUuid, " +
            "name, type, size, md5, sha1, hash, status, " +
            "createtime as createTime, updatetime as updateTime " +
            "from file_info " +
            "where file_uuid = #{fileUuid} " +
            "and status = 1 " +
            "limit 1")
    File getFileByFileUuid(@Param("fileUuid") String fileUuid, @Param("userId") Long userId);

    @Select("select file_id as fileId, file_uuid as fileUuid, name, type, size, md5, sha1, hash, status, count, " +
            "createtime as createTime, updatetime as updateTime from file_info " +
            "where file_uuid = #{fileUuid} and status = 1 for update")
    File getPhysicalFileForUpdate(@Param("fileUuid") String fileUuid);

    /**
     * 查询 getFileByHash 相关逻辑。
     * @return 处理结果
     */
    @Select("select fi.file_id as fileId, fi.file_uuid as fileUuid, uf.is_dir as dir, " +
            "uf.user_id as userId, uf.parent_id as parentId, uf.file_name as name, " +
            "fi.type, fi.size, uf.path, fi.md5, fi.sha1, fi.hash, fi.status, " +
            "fi.createtime as createTime, uf.updatetime as updateTime " +
            "from file_info fi " +
            "join user_file uf on uf.file_uuid = fi.file_uuid " +
            "where fi.hash = #{hash} " +
            "and uf.parent_id = #{parentId} " +
            "and uf.user_id = #{userId} " +
            "and fi.status = 1 " +
            "and uf.status = 1 " +
            "limit 1")
    File getFileByHashInParent(@Param("hash") String hash,
                       @Param("parentId") Long parentId,
                       @Param("userId") Long userId);

    /**
     * 查询 getFileByHash 相关逻辑。
     * @return 处理结果
     */
    @Select("select file_id as fileId, file_uuid as fileUuid, " +
            "name, type, size, md5, sha1, hash, status, count, " +
            "createtime as createTime, updatetime as updateTime " +
            "from file_info " +
            "where hash = #{hash} " +
            "and status = 1 " +
            "limit 1")
    File getFileByHash(@Param("hash") String hash);

    @Select("select file_id as fileId, file_uuid as fileUuid, " +
            "name, type, size, md5, sha1, hash, status, count, " +
            "createtime as createTime, updatetime as updateTime " +
            "from file_info " +
            "where md5 = #{md5} " +
            "and sha1 = #{sha1} " +
            "and size = #{size} " +
            "and status = 1 " +
            "limit 1")
    File getFileByMd5Sha1Size(@Param("md5") String md5,
                              @Param("sha1") String sha1,
                              @Param("size") Long size);

    /**
     * 查询 getUserFileList 相关逻辑。
     * @return 列表结果
     */
    @Select("select ID as id, file_name as fileName, file_uuid as fileUuid, is_dir as dir, " +
            "status, user_id as userId, parent_id as parentId, path, createtime, updatetime " +
            "from user_file " +
            "where parent_id = #{parentId} " +
            "and user_id = #{userId} " +
            "and status = 1 " +
            "order by is_dir desc, updatetime desc")
    List<UserFileDTO> getUserFileList(@Param("parentId") Long parentId, @Param("userId") Long userId);

    /**
     * 查询 listFileByUserId 相关逻辑。
     * @return 列表结果
     */
    @Select("select uf.ID as fileId, fi.file_uuid as fileUuid, uf.is_dir as dir, " +
            "uf.user_id as userId, uf.parent_id as parentId, uf.file_name as name, " +
            "fi.type, fi.size, uf.path, fi.md5, fi.hash, fi.status, " +
            "fi.createtime as createTime, uf.updatetime as updateTime " +
            "from user_file uf " +
            "left join file_info fi on uf.file_uuid = fi.file_uuid " +
            "where uf.user_id = #{userId} " +
            "and uf.status = 1 " +
            "order by uf.is_dir desc, uf.updatetime desc")
    List<FileVO> listFileByUserId(@Param("userId") Long userId);

    /**
     * 查询 getRootDirByUserId 相关逻辑。
     * @return 处理结果
     */
    @Select("select ID as id, file_name as fileName, file_uuid as fileUuid, is_dir as dir, " +
            "status, user_id as userId, parent_id as parentId, path, createtime, updatetime " +
            "from user_file " +
            "where user_id = #{userId} " +
            "and parent_id = 0 " +
            "and file_name = '/' " +
            "and is_dir = 1 " +
            "and status = 1 " +
            "limit 1")
    UserFileDTO getRootDirByUserId(@Param("userId") Long userId);

    /**
     * 查询 listFileVOByparentId 相关逻辑。
     * @return 列表结果
     */
    @Select("select ID as id, file_name as fileName, file_uuid as fileUuid, is_dir as dir, " +
            "status, user_id as userId, parent_id as parentId, path, createtime, updatetime " +
            "from user_file " +
            "where user_id = #{userId} " +
            "and parent_id = #{parentId} " +
            "and status = 1 " +
            "order by is_dir desc, updatetime desc")
    List<UserFileDTO> listFileVOByparentId(@Param("parentId") Long parentId,
                                           @Param("userId") Long userId);

    /**
     * 查询 listFileByparentId 相关逻辑。
     * @return 列表结果
     */
    @Select("select ID as id, file_name as fileName, file_uuid as fileUuid, is_dir as dir, " +
            "status, user_id as userId, parent_id as parentId, path, createtime, updatetime " +
            "from user_file " +
            "where user_id = #{userId} " +
            "and parent_id = #{parentId} " +
            "and status = 1 " +
            "order by is_dir desc, updatetime desc")
    List<UserFileDTO> listFileByparentId(@Param("parentId") Long parentId,
                                         @Param("userId") Long userId);

    /**
     * 查询 listFileByparentIdAny 相关逻辑。
     * @return 列表结果
     */
    @Select("select ID as id, file_name as fileName, file_uuid as fileUuid, is_dir as dir, " +
            "status, user_id as userId, parent_id as parentId, path, createtime, updatetime " +
            "from user_file " +
            "where parent_id = #{parentId} " +
            "and status = 1 " +
            "order by is_dir desc, updatetime desc")
    List<UserFileDTO> listFileByparentIdAny(@Param("parentId") Long parentId);

    /**
     * 查询 listFileByparentIdActiveOrRecycle 相关逻辑。
     * @return 列表结果
     */
    @Select("select ID as id, file_name as fileName, file_uuid as fileUuid, is_dir as dir, " +
            "status, user_id as userId, parent_id as parentId, path, createtime, updatetime " +
            "from user_file " +
            "where user_id = #{userId} " +
            "and parent_id = #{parentId} " +
            "and status in (1, 2) " +
            "order by is_dir desc, updatetime desc")
    List<UserFileDTO> listFileByparentIdActiveOrRecycle(@Param("parentId") Long parentId,
                                                        @Param("userId") Long userId);

    /**
     * 查询 listFileByparentIdAnyActiveOrRecycle 相关逻辑。
     * @return 列表结果
     */
    @Select("select ID as id, file_name as fileName, file_uuid as fileUuid, is_dir as dir, " +
            "status, user_id as userId, parent_id as parentId, path, createtime, updatetime " +
            "from user_file " +
            "where parent_id = #{parentId} " +
            "and status in (1, 2) " +
            "order by is_dir desc, updatetime desc")
    List<UserFileDTO> listFileByparentIdAnyActiveOrRecycle(@Param("parentId") Long parentId);

    /**
     * 查询 listRecycleRootByUserId 相关逻辑。
     * @return 列表结果
     */
    @Select("select uf.ID as id, uf.file_name as fileName, uf.file_uuid as fileUuid, uf.is_dir as dir, " +
            "uf.status, uf.user_id as userId, uf.parent_id as parentId, uf.path, uf.createtime, uf.updatetime " +
            "from user_file uf " +
            "left join user_file parent on parent.ID = uf.parent_id " +
            "where uf.user_id = #{userId} " +
            "and uf.status = 2 " +
            "and (parent.ID is null or parent.status <> 2) " +
            "order by uf.updatetime desc")
    List<UserFileDTO> listRecycleRootByUserId(@Param("userId") Long userId);

    /**
     * 查询 listRecycleRootAny 相关逻辑。
     * @return 列表结果
     */
    @Select("select uf.ID as id, uf.file_name as fileName, uf.file_uuid as fileUuid, uf.is_dir as dir, " +
            "uf.status, uf.user_id as userId, uf.parent_id as parentId, uf.path, uf.createtime, uf.updatetime " +
            "from user_file uf " +
            "left join user_file parent on parent.ID = uf.parent_id " +
            "where uf.status = 2 " +
            "and (parent.ID is null or parent.status <> 2) " +
            "order by uf.updatetime desc")
    List<UserFileDTO> listRecycleRootAny();

    /**
     * 新增 insertFileInfo 相关逻辑。
     * @return 影响行数
     */
    @Options(useGeneratedKeys = true, keyProperty = "fileId", keyColumn = "file_id")
    @Insert("insert into file_info(file_uuid, name, type, size, md5, sha1, hash, status, count, createtime, updatetime) " +
            "values " +
            "(#{fileUuid}, #{name}, #{type}, #{size}, #{md5}, #{sha1}, #{hash}, #{status}, #{count}, #{createTime}, #{updateTime})")
    int insertFileInfo(File file);

    /**
     * 更新 updateName 相关逻辑。
     * @return 影响行数
     */
    @Update("update user_file " +
            "set file_name = #{newName}, " +
            "updatetime = #{updateTime} " +
            "where file_uuid = #{fileUuid} " +
            "and user_id = #{userId} " +
            "and status = 1")
    int updateName(@Param("fileUuid") String fileUuid,
                   @Param("newName") String newName,
                   @Param("userId") Long userId,
                   @Param("updateTime") LocalDateTime updateTime);

    /**
     * 更新 updateNameById 相关逻辑。
     * @return 影响行数
     */
    @Update("update user_file " +
            "set file_name = #{newName}, " +
            "updatetime = #{updateTime} " +
            "where ID = #{id} " +
            "and status = 1")
    int updateNameById(@Param("id") Long id,
                       @Param("newName") String newName,
                       @Param("updateTime") LocalDateTime updateTime);

    @Update("update user_file set file_name = #{newName}, updatetime = #{updateTime} " +
            "where ID = #{id} and status = 2")
    int updateRecycledNameById(@Param("id") Long id,
                               @Param("newName") String newName,
                               @Param("updateTime") LocalDateTime updateTime);

    @Select("select count(1) from user_file where user_id = #{userId} and parent_id = #{parentId} " +
            "and file_name = #{fileName} and is_dir = #{dir} and status = 1 and ID <> #{excludeId}")
    int countActiveNameExcluding(@Param("userId") Long userId,
                                 @Param("parentId") Long parentId,
                                 @Param("fileName") String fileName,
                                 @Param("dir") Integer dir,
                                 @Param("excludeId") Long excludeId);

    /**
     * 更新 updateStatusById 相关逻辑。
     * @return 影响行数
     */
    @Update("update user_file " +
            "set status = #{status}, " +
            "updatetime = #{updateTime} " +
            "where ID = #{id}")
    int updateStatusById(@Param("id") Long id,
                         @Param("status") Integer status,
                         @Param("updateTime") LocalDateTime updateTime);

    /**
     * 删除 deleteByfileUuid 相关逻辑。
     * @return 影响行数
     */
    @Delete("delete from user_file " +
            "where file_uuid = #{fileUuid} " +
            "and user_id = #{userId} " +
            "and parent_id = #{parentId}")
    int deleteByfileUuid(@Param("fileUuid") String fileUuid,@Param("parentId") Long parentId,@Param("userId") Long userId);

    /**
     * 删除 deleteByFileId 相关逻辑。
     * @return 影响行数
     */
    @Delete("delete from user_file " +
            "where ID = #{id} " +
            "and user_id = #{userId}")
    int deleteByFileId(@Param("id")Long id,@Param("userId") Long userId);

    /**
     * 删除 deleteByFileIdAny 相关逻辑。
     * @return 影响行数
     */
    @Delete("delete from user_file " +
            "where ID = #{id}")
    int deleteByFileIdAny(@Param("id")Long id);

    /**
     * 查找 findFileByName 相关逻辑。
     * @return 处理结果
     */
    @Select("select fi.file_id as fileId, fi.file_uuid as fileUuid, uf.is_dir as dir, " +
            "uf.user_id as userId, uf.parent_id as parentId, uf.file_name as name, " +
            "fi.type, fi.size, uf.path, fi.md5, fi.hash, fi.status, " +
            "fi.createtime as createTime, uf.updatetime as updateTime " +
            "from user_file uf " +
            "left join file_info fi on uf.file_uuid = fi.file_uuid " +
            "where uf.file_name = #{name} " +
            "and uf.user_id = #{userId} " +
            "and uf.parent_id = #{parentId} " +
            "and uf.status = 1 " +
            "limit 1")
    File findFileByName(@Param("name") String name,
                        @Param("userId") Long userId,
                        @Param("parentId") Long parentId);

    /**
     * 执行 ParentIdExist 函数的业务处理。
     * @return 处理结果
     */
    @Select("select count(1) > 0 " +
            "from user_file " +
            "where ID = #{parentId} " +
            "and user_id = #{userId} " +
            "and is_dir = 1 " +
            "and status = 1")
    boolean ParentIdExist(@Param("parentId") Long parentId, @Param("userId") Long userId);

    /**
     * 执行 ParentIdExistAny 函数的业务处理。
     * @return 处理结果
     */
    @Select("select count(1) > 0 " +
            "from user_file " +
            "where ID = #{parentId} " +
            "and is_dir = 1 " +
            "and status = 1")
    boolean ParentIdExistAny(@Param("parentId") Long parentId);

    /**
     * 更新 updateParent 相关逻辑。
     * @return 影响行数
     */
    @Update("update user_file " +
            "set parent_id = #{newParentId}, " +
            "updatetime = #{timenow} " +
            "where ID = #{fileId}")
    int updateParent(@Param("fileId") Long fileId,
                     @Param("newParentId") Long newParentId,
                     @Param("timenow") LocalDateTime timenow);

    /**
     * 执行 myfather 函数的业务处理。
     * @return 处理结果
     */
    @Select("select parent_id " +
            "from user_file " +
            "where ID = #{fileId} " +
            "and user_id = #{userId} " +
            "and status = 1")
    Long myfather(@Param("fileId") String fileId, @Param("userId") Long userId);

    /**
     * 新增 insertFile_User 相关逻辑。
     * @return 影响行数
     */
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "ID")
    @Insert("insert into user_file(file_name, file_uuid, is_dir, status, user_id, parent_id, path, createtime, updatetime) " +
            "values " +
            "(#{fileName}, #{fileUuid}, #{dir}, #{status}, #{userId}, #{parentId}, #{path}, #{createtime}, #{updatetime})")
    int insertFile_User(UserFileDTO fileUserDTO);

    /**
     * 删除 delete_fileinfo_ByfileUuid 相关逻辑。
     * @return 影响行数
     */
    @Delete("delete from file_info " +
            "where file_uuid = #{fileUuid}")
    int delete_fileinfo_ByfileUuid(@Param("fileUuid") String fileUuid);

    /**
     * 查询 getFileStatus 相关逻辑。
     * @return 处理结果
     */
    @Select("select count(1) > 0 " +
            "from file_info " +
            "where file_uuid = #{fileUuid} " +
            "and status = 1")
    boolean getFileStatus(@Param("fileUuid") String fileUuid);

    /**
     * 更新 updatePath 相关逻辑。
     */
    @Update("update user_file " +
            "set path = #{path} " +
            "where ID = #{id} " +
            "and file_uuid = #{fileUuid} " +
            "and user_id = #{userId}")
    void updatePath(@Param("id")Long id,@Param("fileUuid") String fileUuid,@Param("path") String path,@Param("userId") Long userId);

    /**
     * 更新 updateParent 相关逻辑。
     */
    @Update("update user_file " +
            "set parent_id = #{parentId} " +
            "where ID = #{id} " +
            "and file_uuid = #{fileUuid} " +
            "and user_id = #{userId}")
    void updateParentWithUuid(@Param("id") Long id,@Param("fileUuid") String fileUuid,@Param("parentId") Long parentId,@Param("userId") Long userId);

    /**
     * 更新 updateFileCount 相关逻辑。
     */
    @Update("update file_info " +
            "set count = count + #{count} " +
            "where file_uuid = #{fileUuid} " +
            "and count + #{count} >= 0")
    int updateFileCount(@Param("fileUuid") String fileUuid,
                        @Param("count") Integer count);

    /**
     * 更新 updatePhysicalFileInfo 相关逻辑。
     * @return 影响行数
     */
    @Update("update file_info set name = #{name}, type = #{type}, size = #{size}, md5 = #{md5}, sha1 = #{sha1}, hash = #{hash}, updatetime = #{updateTime} " +
            "where file_uuid = #{fileUuid} and status = 1")
    int updatePhysicalFileInfo(@Param("fileUuid") String fileUuid,
                               @Param("name") String name,
                               @Param("type") String type,
                               @Param("size") Long size,
                               @Param("md5") String md5,
                               @Param("sha1") String sha1,
                               @Param("hash") String hash,
                               @Param("updateTime") LocalDateTime updateTime);

    /**
     * 查询 getFileCount 相关逻辑。
     * @return 影响行数
     */
    @Select("select count from file_info " +
            "where file_uuid = #{fileUuid}")
    int getFileCount(@Param("fileUuid") String fileUuid);

    @Select("select coalesce(sum(fi.size), 0) " +
            "from (select distinct file_uuid from user_file where user_id = #{userId} and is_dir = 0 and status = 1) uf " +
            "join file_info fi on fi.file_uuid = uf.file_uuid and fi.status = 1")
    Long sumUserStorageBytes(@Param("userId") Long userId);

    @Select("select count(distinct uf.file_uuid) " +
            "from user_file uf " +
            "join file_info fi on fi.file_uuid = uf.file_uuid and fi.status = 1 " +
            "where uf.user_id = #{userId} and uf.is_dir = 0 and uf.status = 1")
    Integer countUserStorageFiles(@Param("userId") Long userId);

    @Select("select count(1) from user_file where user_id = #{userId} and file_uuid = #{fileUuid} " +
            "and is_dir = 0 and status = 1")
    Integer countUserActiveByFileUuid(@Param("userId") Long userId, @Param("fileUuid") String fileUuid);

    /**
     * 查询用户所有文件（含 file_info 元数据），用于账号删除时全部文件清理。
     */
    @Select("select fi.file_id as fileId, fi.file_uuid as fileUuid, uf.is_dir as dir, " +
            "uf.user_id as userId, uf.parent_id as parentId, uf.file_name as name, " +
            "fi.type, fi.size, uf.path, fi.md5, fi.sha1, fi.hash, fi.status, " +
            "fi.createtime as createTime, uf.updatetime as updateTime " +
            "from user_file uf " +
            "left join file_info fi on uf.file_uuid = fi.file_uuid " +
            "where uf.user_id = #{userId} and uf.status = 1")
    List<File> listAllByUserId(@Param("userId") Long userId);

    /**
     * 软删除用户文件记录（status=0），用于账号删除时清理。
     */
    @Update("update user_file set status = 0, updatetime = now() " +
            "where file_uuid = #{fileUuid} and user_id = #{userId} and status = 1")
    int softDeleteByFileUuid(@Param("fileUuid") String fileUuid, @Param("userId") Long userId);
}
