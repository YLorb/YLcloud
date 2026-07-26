package com.ylcloud.service;

import com.ylcloud.Exception.BaseException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeParseException;

@Service
@RequiredArgsConstructor
public class OpenApiVersionPolicyService {
    public static final String LATEST = "v1";
    private final SiteSettingService settings;

    public void requireWriteAllowed(String version) {
        String configured = settings.getString("api." + version + ".readOnlyAfter","");
        if(configured == null || configured.isBlank()) return;
        try {
            if(!Instant.parse(configured.trim()).isAfter(Instant.now())) {
                throw new BaseException(410,"API 版本已进入只读阶段");
            }
        } catch(DateTimeParseException exception) {
            throw new BaseException(503,"API 版本策略配置无效");
        }
    }

    public int pageSize(Integer requested) {
        int maximum = Math.max(1,settings.getLong("api.maxPageSize",100L).intValue());
        if(requested == null) return Math.min(50,maximum);
        if(requested < 1 || requested > maximum) throw new BaseException("pageSize 必须在 1 到 " + maximum + " 之间");
        return requested;
    }
}
