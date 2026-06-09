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
     * 查询指定上传任务下的某个分片记录。
     *
     * @param uploadId 上传任务 ID
     * @param chunkIndex 分片序号，从 0 开始
     * @return 分片记录，不存在时返回 null
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
     * 查询指定上传任务已上传的分片序号列表。
     *
     * @param uploadId 上传任务 ID
     * @return 已上传分片序号列表
     */
    @Select("select chunk_index " +
            "from upload_chunk " +
            "where upload_id = #{uploadId} " +
            "and status = 1 " +
            "order by chunk_index")
    List<Integer> listUploadedIndexes(@Param("uploadId") String uploadId);

    /**
     * 查询指定上传任务的分片对象名列表，按分片序号升序排列。
     *
     * @param uploadId 上传任务 ID
     * @return MinIO 分片对象名列表
     */
    @Select("select object_name " +
            "from upload_chunk " +
            "where upload_id = #{uploadId} " +
            "and status = 1 " +
            "order by chunk_index")
    List<String> listObjectNames(@Param("uploadId") String uploadId);

    /**
     * 新增分片上传记录。
     *
     * @param uploadChunk 分片记录实体
     * @return 影响行数
     */
    @Insert("insert into upload_chunk(upload_id, chunk_index, chunk_md5, size, object_name, status, createtime, updatetime) " +
            "values(#{uploadId}, #{chunkIndex}, #{chunkMd5}, #{size}, #{objectName}, #{status}, #{createtime}, #{updatetime})")
    int insert(UploadChunk uploadChunk);
}
