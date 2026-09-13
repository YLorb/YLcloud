package com.ylcloud.DTO;

import lombok.Data;

@Data
public class RestoreVerificationDTO {
    private String verificationKey;
    private String status;
    private String restoreEnvironment;
    private Boolean mysqlRestored;
    private Boolean minioRestored;
    private Boolean qdrantRestored;
    private Boolean configRestored;
    private Boolean businessSampleCheck;
    private String errorMessage;
}
