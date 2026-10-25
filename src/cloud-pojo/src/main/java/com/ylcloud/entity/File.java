package com.ylcloud.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor // 保留 new file();
@AllArgsConstructor
public class File {
    private Long fileId;

    private String fileUuid;

    private boolean dir;

    private Long userId;

    private Long parentId;

    private Long size;

    private Integer count;

    private String name;

    private String type;

    private String path;

    private String md5;

    private String sha1;

    private String hash;

    /** Internal read pin, never accepted from or exposed to an API client. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private transient String minioVersionId;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public boolean getDir() {
        return dir;
    }
}
