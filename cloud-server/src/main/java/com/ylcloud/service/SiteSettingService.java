package com.ylcloud.service;

import com.ylcloud.DTO.SiteSettingUpdateDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.VO.PublicSiteSettingVO;
import com.ylcloud.VO.SiteSettingVO;
import com.ylcloud.entity.SiteSetting;
import com.ylcloud.mapper.SiteSettingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class SiteSettingService {
    public static final String SITE_NAME = "site.name";
    public static final String SITE_DESCRIPTION = "site.description";
    public static final String SITE_LOGO_URL = "site.logoUrl";
    public static final String SITE_PUBLIC_URL = "site.publicUrl";
    public static final String SITE_ALLOW_REGISTER = "site.allowRegister";
    public static final String RAG_MODEL_SERVICE_BASE_URL = "rag.modelServiceBaseUrl";

    private final SiteSettingMapper siteSettingMapper;

    public List<SiteSettingVO> listForAdmin() {
        return siteSettingMapper.listAll().stream().map(this::toVO).toList();
    }

    public PublicSiteSettingVO publicSettings() {
        return PublicSiteSettingVO.builder()
                .siteName(getString(SITE_NAME,"YL Cloud"))
                .siteDescription(getString(SITE_DESCRIPTION,"轻量、清晰、可用的个人网盘"))
                .logoUrl(getString(SITE_LOGO_URL,""))
                .publicUrl(getString(SITE_PUBLIC_URL,""))
                .allowRegister(getBoolean(SITE_ALLOW_REGISTER,true))
                .build();
    }

    @Transactional
    public void updateBatch(SiteSettingUpdateDTO dto) {
        for(SiteSettingUpdateDTO.Item item : dto.getSettings()) {
            SiteSetting setting = siteSettingMapper.getByKey(item.getKey());
            if(setting == null) {
                throw new BaseException("未知配置项：" + item.getKey());
            }
            if(setting.getEditable() == null || setting.getEditable() != 1) {
                throw new BaseException("配置项不可编辑：" + item.getKey());
            }
            String nextValue = item.getValue();
            if(isSecret(setting) && (nextValue == null || nextValue.isBlank())) {
                continue;
            }
            siteSettingMapper.updateValue(item.getKey(),nextValue == null ? "" : nextValue);
        }
    }

    public String getString(String key,String fallback) {
        SiteSetting setting = siteSettingMapper.getByKey(key);
        if(setting == null || setting.getSettingValue() == null || setting.getSettingValue().isBlank()) {
            return fallback;
        }
        return setting.getSettingValue();
    }

    public Boolean getBoolean(String key,Boolean fallback) {
        String value = getString(key,null);
        if(value == null) {
            return fallback;
        }
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }

    private SiteSettingVO toVO(SiteSetting setting) {
        boolean secret = isSecret(setting);
        return SiteSettingVO.builder()
                .key(setting.getSettingKey())
                .value(secret ? "" : nullToEmpty(setting.getSettingValue()))
                .maskedValue(secret ? maskSecret(setting.getSettingValue()) : null)
                .valueType(setting.getValueType())
                .groupName(setting.getGroupName())
                .label(setting.getLabel())
                .description(setting.getDescription())
                .secret(secret)
                .editable(setting.getEditable() != null && setting.getEditable() == 1)
                .build();
    }

    private boolean isSecret(SiteSetting setting) {
        return setting.getSecret() != null && setting.getSecret() == 1;
    }

    private String maskSecret(String value) {
        if(value == null || value.isBlank()) {
            return "未配置";
        }
        String normalized = value.trim();
        if(normalized.length() <= 8) {
            return "已配置";
        }
        return normalized.substring(0,Math.min(3,normalized.length())) + "****" +
                normalized.substring(normalized.length() - 4).toLowerCase(Locale.ROOT);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
