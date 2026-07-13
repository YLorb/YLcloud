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

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SiteSettingService {
    private static final Set<String> REQUIRED_KEYS = Set.of(
            "site.name","site.publicUrl","llm.provider","llm.model"
    );
    private static final Set<String> HTTP_URL_KEYS = Set.of(
            "site.logoUrl","site.publicUrl","share.publicBaseUrl","llm.baseUrl",
            "rag.modelServiceBaseUrl","rag.parserServiceBaseUrl"
    );
    private static final Set<String> SECRET_REFERENCE_PREFIXES = Set.of(
            "env:","docker-secret:","vault:","azure-key-vault:","aws-secrets-manager:"
    );

    public static final String SITE_NAME = "site.name";
    public static final String SITE_DESCRIPTION = "site.description";
    public static final String SITE_LOGO_URL = "site.logoUrl";
    public static final String SITE_PUBLIC_URL = "site.publicUrl";
    public static final String SITE_ALLOW_REGISTER = "site.allowRegister";
    public static final String UPLOAD_MAX_FILE_SIZE = "upload.maxFileSize";
    public static final String LLM_ENABLED = "llm.enabled";
    public static final String LEGACY_LLM_API_KEY = "llm.apiKey";
    public static final String LLM_API_KEY_REF = "llm.apiKeyRef";
    public static final String RAG_MODEL_SERVICE_BASE_URL = "rag.modelServiceBaseUrl";

    private final SiteSettingMapper siteSettingMapper;

    public List<SiteSettingVO> listForAdmin() {
        return siteSettingMapper.listAll().stream()
                .filter(setting -> !LEGACY_LLM_API_KEY.equals(setting.getSettingKey()))
                .map(this::toVO)
                .toList();
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
            if(isSecret(setting)) {
                throw new BaseException("密钥不能通过管理页面保存，请配置密钥引用：" + item.getKey());
            }
            String nextValue = item.getValue();
            String normalizedValue = normalizeAndValidate(setting,nextValue);
            if(siteSettingMapper.updateValue(item.getKey(),normalizedValue) == 0) {
                throw new BaseException("配置项保存失败：" + item.getKey());
            }
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

    public Long getLong(String key,Long fallback) {
        String value = getString(key,null);
        if(value == null) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed >= 0 ? parsed : fallback;
        } catch(NumberFormatException ignored) {
            return fallback;
        }
    }

    private String normalizeAndValidate(SiteSetting setting, String value) {
        String raw = value == null ? "" : value;
        String normalized = raw.trim();
        String key = setting.getSettingKey();
        if(REQUIRED_KEYS.contains(key) && normalized.isEmpty()) {
            throw new BaseException("配置项不能为空：" + key);
        }
        if(HTTP_URL_KEYS.contains(key) && !normalized.isEmpty()) {
            validateHttpUrl(key,normalized);
        }
        if(LLM_API_KEY_REF.equals(key) && !normalized.isEmpty() && SECRET_REFERENCE_PREFIXES.stream().noneMatch(normalized::startsWith)) {
            throw new BaseException("密钥引用格式不合法：" + key);
        }

        String valueType = setting.getValueType() == null ? "string" : setting.getValueType().toLowerCase(Locale.ROOT);
        if("boolean".equals(valueType)) {
            if(Set.of("true","1","yes").contains(normalized.toLowerCase(Locale.ROOT))) {
                return "true";
            }
            if(Set.of("false","0","no").contains(normalized.toLowerCase(Locale.ROOT))) {
                return "false";
            }
            throw new BaseException("布尔配置值不合法：" + key);
        }
        if("number".equals(valueType)) {
            try {
                BigDecimal number = new BigDecimal(normalized);
                if(number.signum() < 0) {
                    throw new BaseException("数字配置不能小于 0：" + key);
                }
                if(UPLOAD_MAX_FILE_SIZE.equals(key) && (number.scale() > 0 || number.longValueExact() <= 0)) {
                    throw new BaseException("单文件大小上限必须是正整数");
                }
            } catch(ArithmeticException | NumberFormatException exception) {
                throw new BaseException("数字配置值不合法：" + key);
            }
            return normalized;
        }
        return isSecret(setting) ? raw : normalized;
    }

    private void validateHttpUrl(String key, String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if(uri.getHost() == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("unsupported url");
            }
        } catch(IllegalArgumentException exception) {
            throw new BaseException("URL 配置值不合法：" + key);
        }
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
                .createTime(setting.getCreateTime())
                .updateTime(setting.getUpdateTime())
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
