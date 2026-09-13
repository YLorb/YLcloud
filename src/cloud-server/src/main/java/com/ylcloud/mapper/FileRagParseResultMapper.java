package com.ylcloud.mapper;

import com.ylcloud.entity.FileRagParseResult;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Mapper for cached structured RAG parse results.
 */
@Mapper
public interface FileRagParseResultMapper {

    @Select("select id, file_uuid as `fileUuid`, file_hash as `fileHash`, parser, parser_version as `parserVersion`, " +
            "parse_status as `parseStatus`, full_text as `fullText`, blocks_json as `blocksJson`, error_message as `errorMessage`, " +
            "status, createtime, updatetime from file_rag_parse_result " +
            "where file_uuid = #{fileUuid} and file_hash = #{fileHash} and parser_version = #{parserVersion} and status = 1 " +
            "order by id desc limit 1")
    FileRagParseResult getByFileAndVersion(@Param("fileUuid") String fileUuid,
                                           @Param("fileHash") String fileHash,
                                           @Param("parserVersion") String parserVersion);

    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("insert into file_rag_parse_result(file_uuid, file_hash, parser, parser_version, parse_status, full_text, blocks_json, error_message, status, createtime, updatetime) " +
            "values(#{fileUuid}, #{fileHash}, #{parser}, #{parserVersion}, #{parseStatus}, #{fullText}, #{blocksJson}, #{errorMessage}, #{status}, #{createtime}, #{updatetime})")
    int insert(FileRagParseResult result);

    /**
     * 禁用 metadata fallback 解析缓存，避免旧失败缓存被反复展示或复用。
     * @return 影响行数
     */
    @Update("update file_rag_parse_result set status = 0, updatetime = now() " +
            "where file_uuid = #{fileUuid} and file_hash = #{fileHash} and parser_version = #{parserVersion} " +
            "and parser = 'metadata' and status = 1")
    int disableMetadataCache(@Param("fileUuid") String fileUuid,
                             @Param("fileHash") String fileHash,
                             @Param("parserVersion") String parserVersion);

    /**
     * 禁用与当前文件类型不兼容的历史解析缓存。
     */
    @Update("update file_rag_parse_result set status = 0, updatetime = now() where id = #{id} and status = 1")
    int disableById(@Param("id") Long id);

    @Update("update file_rag_parse_result set status = 0, updatetime = now() where file_uuid = #{fileUuid} and status = 1")
    int disableByFileUuid(@Param("fileUuid") String fileUuid);
}
