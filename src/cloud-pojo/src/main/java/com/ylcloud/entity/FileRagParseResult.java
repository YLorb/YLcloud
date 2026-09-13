package com.ylcloud.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Cached structured parse result for a physical file.
 */
@Data
public class FileRagParseResult {
    private Long id;
    private String fileUuid;
    private String fileHash;
    private String parser;
    private String parserVersion;
    private String parseStatus;
    private String fullText;
    private String blocksJson;
    private String errorMessage;
    private Integer status;
    private LocalDateTime createtime;
    private LocalDateTime updatetime;
}
