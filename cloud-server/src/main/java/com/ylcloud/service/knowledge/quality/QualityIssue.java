package com.ylcloud.service.knowledge.quality;

public record QualityIssue(String field,
                           String code,
                           String severity,
                           String message,
                           boolean repairable) {
}
