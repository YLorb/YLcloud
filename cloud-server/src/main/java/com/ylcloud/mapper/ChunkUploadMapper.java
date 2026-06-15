package com.ylcloud.mapper;

import com.ylcloud.entity.UploadChunk;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 分片上传记录表 Mapper。
 */
@Mapper
public interface ChunkUploadMapper {

    /**
     * 查询 getByUploadIdAndIndex 相关逻辑。
     * @return 处理结果
     */
    @Select("select id, upload_id as uploadId, chunk_index as chunkIndex, chunk_md5 as chunkMd5, " +
            "size, object_name as objectName, status, createtime, updatetime " +
            "from upload_chunk " +
            "where upload_id = #{uploadId} " +
            "and chunk_index = #{chunkIndex} " +
            "and status = 1 " +
            "limit 1")
    UploadChunk getByUploadIdAndIndex(@Param("uploadId") String uploadId, @Param("chunkIndex") Integer chunkIndex);

    /**
     * 查询 listUploadedIndexes 相关逻辑。
     * @return 列表结果
     */
    @Select("select chunk_index " +
            "from upload_chunk " +
            "where upload_id = #{uploadId} " +
            "and status = 1 " +
            "order by chunk_index")
    List<Integer> listUploadedIndexes(@Param("uploadId") String uploadId);

    /**
     * 查询 listObjectNames 相关逻辑。
     * @return 列表结果
     */
    @Select("select object_name " +
            "from upload_chunk " +
            "where upload_id = #{uploadId} " +
            "and status = 1 " +
            "order by chunk_index")
    List<String> listObjectNames(@Param("uploadId") String uploadId);

    /**
     * 新增 insert 相关逻辑。
     * @return 影响行数
     */
    @Insert("insert into upload_chunk(upload_id, chunk_index, chunk_md5, size, object_name, status, createtime, updatetime) " +
            "values(#{uploadId}, #{chunkIndex}, #{chunkMd5}, #{size}, #{objectName}, #{status}, #{createtime}, #{updatetime})")
    int insert(UploadChunk uploadChunk);

    /**
     * 新增 insertIgnore 相关逻辑。
     * @return 影响行数
     */
    @Insert("insert ignore into upload_chunk(upload_id, chunk_index, chunk_md5, size, object_name, status, createtime, updatetime) " +
            "values(#{uploadId}, #{chunkIndex}, #{chunkMd5}, #{size}, #{objectName}, #{status}, #{createtime}, #{updatetime})")
    int insertIgnore(UploadChunk uploadChunk);
}
