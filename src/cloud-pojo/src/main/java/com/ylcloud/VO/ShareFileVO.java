package com.ylcloud.VO;

import lombok.Data;

import java.util.List;

@Data
public class ShareFileVO {
    private Long fileId;

    private String name;

    private boolean dir;

    private String previewType;

    private String contentType;

    private Long size;

    private String previewUrl;

    private String downloadUrl;

    private String textContent;

    private List<ShareFileVO> children;
}
