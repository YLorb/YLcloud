package com.ylcloud.VO;

import lombok.Data;

@Data
public class FilePreviewVO {
    private String fileUuid;

    private String name;

    private String previewType;

    private String contentType;

    private Long size;

    private String previewUrl;

    private String textContent;
}
