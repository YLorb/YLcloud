package com.ylcloud.VO;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FileVO {
    private Long fileId;

    private String fileUuid;

    private boolean dir;

    @JsonProperty("isDir")
    public boolean isDir() {
        return dir;
    }

    @JsonProperty("isDir")
    public void setDir(boolean dir) {
        this.dir = dir;
    }

    private Long userId;

    private Long parentId;

    private String name;

    private String type;

    private Long size;

    private String hash;

    private String path;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
