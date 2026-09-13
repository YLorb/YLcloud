package com.ylcloud.VO;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PublicSiteSettingVO {
    private String siteName;
    private String siteDescription;
    private String logoUrl;
    private String publicUrl;
    private Boolean allowRegister;
    private Long multipartUploadThresholdBytes;
}
