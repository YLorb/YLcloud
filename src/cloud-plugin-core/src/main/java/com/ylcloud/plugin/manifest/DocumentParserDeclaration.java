package com.ylcloud.plugin.manifest;

import com.ylcloud.plugin.spi.document.DocumentMediaType;
import com.ylcloud.plugin.spi.document.DocumentParseOptions;

import java.util.Objects;
import java.util.Set;

/**
 * 文档解析能力的静态声明，不证明模型实际可用。
 * 支持某项能力与默认开启该项能力分开：不支持的能力不能设为默认开启。
 * languages 为空表示不接受显式语言选择；defaultOptions.language 为 null 表示未声明默认语言。
 * Manifest 默认开关必须明确为 true/false，与单次请求的 null（继承默认）不同。
 */
public record DocumentParserDeclaration(Set<String> mediaTypes, Set<String> languages,
                                        boolean supportsTables, boolean supportsFormulas,
                                        DocumentParseOptions defaultOptions) {
    public DocumentParserDeclaration {
        mediaTypes = Set.copyOf(mediaTypes);
        languages = Set.copyOf(languages);
        Objects.requireNonNull(defaultOptions, "defaultOptions");
        if (mediaTypes.isEmpty() || mediaTypes.size() > 64 || languages.size() > 64) {
            throw new IllegalArgumentException("invalid declaration collection size");
        }
        for (String mediaType : mediaTypes) {
            if (!new DocumentMediaType(mediaType).value().equals(mediaType)) {
                throw new IllegalArgumentException("media types must be canonical");
            }
        }
        for (String language : languages) {
            if (!new DocumentParseOptions(language, null, null).language().equals(language)) {
                throw new IllegalArgumentException("language tags must be canonical");
            }
        }
        String defaultLanguage = defaultOptions.language();
        if (defaultLanguage != null && !languages.contains(defaultLanguage)) {
            throw new IllegalArgumentException("default language is not declared as supported");
        }
        if (defaultOptions.recognizeTables() == null || defaultOptions.recognizeFormulas() == null) {
            throw new IllegalArgumentException("manifest recognition defaults must be explicit");
        }
        if ((!supportsTables && defaultOptions.recognizeTables())
                || (!supportsFormulas && defaultOptions.recognizeFormulas())) {
            throw new IllegalArgumentException("cannot enable an unsupported recognition feature by default");
        }
    }
}
