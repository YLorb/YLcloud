package com.ylcloud.VO;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件历史版本展示对象。
 */
@Data
public class FileVersionVO {
    private Long id;
    private String fileUuid;
    private Integer versionNo;
    private String minioVersionId;
    private String fileName;
    private String fileHash;
    private String fileMd5;
    private String fileType;
    private Long fileSize;
    private String changeNote;
    private Long createdBy;
    private Integer current;
    private LocalDateTime createtime;
    private String previewUrl;
    private String streamUrl;
    private String downloadUrl;
}
