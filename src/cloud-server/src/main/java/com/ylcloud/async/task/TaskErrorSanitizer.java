package com.ylcloud.async.task;

import org.springframework.stereotype.Component;

@Component
public class TaskErrorSanitizer {
    public String sanitize(String value) {
        if(value == null || value.isBlank()) return null;
        String sanitized = value
                .replaceAll("(?i)(bearer\\s+)[a-z0-9._~+\\-/]+=*","$1[REDACTED]")
                .replaceAll("(?i)(api[-_ ]?key|token|password|secret)(\\s*[:=]\\s*)[^\\s,;]+","$1$2[REDACTED]")
                .replaceAll("(?i)https?://[^\\s?]+\\?[^\\s]+","[REDACTED_URL]")
                .replaceAll("[\\r\\n\\t]+"," ");
        return sanitized.length() <= 1000 ? sanitized : sanitized.substring(0,1000);
    }
}
