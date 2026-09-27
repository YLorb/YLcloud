package com.ylcloud.plugin.spi.document;

import java.util.IllformedLocaleException;
import java.util.Locale;

/**
 * 本次文档解析的可选配置，用于覆盖 Provider 的默认配置。
 * {@code null} 表示本次未指定，不代表自动检测，也不代表关闭功能。
 * 默认配置如何保存、如何映射到引擎，由后续接入处理；本类型只表达选项和校验语言标签语法。
 *
 * <p>构造参数依次为：识别语言、表格识别开关、公式识别开关。例如：
 * <pre>{@code
 * // 指定中文（中国）语言标签，关闭表格识别，开启公式识别。
 * new DocumentParseOptions("zh-CN", false, true);
 *
 * // 三项都未指定，沿用 Provider 默认配置；不是把两个识别开关设为 false。
 * DocumentParseOptions.defaults();
 * }</pre>
 *
 * <p>语言使用 BCP 47 标签，例如 {@code zh-CN}，不直接使用引擎别名（如 PaddleOCR 的 ch）。
 * 标签格式合法不保证 Provider 或其模型支持该语言。Provider 必须映射并验证显式选项；
 * 不支持时返回 {@code UNSUPPORTED_OPTION}，不能偷偷改用默认语言。
 * 后续缓存需要区分实际生效的配置，不能只根据原始请求中的 null 判断配置相同。
 *
 * @param language 识别语言；null 沿用 Provider 默认语言，空白值和 auto 不被接受
 * @param recognizeTables 表格识别开关：null 沿用默认值，true 明确开启，false 明确关闭
 * @param recognizeFormulas 公式识别开关：null 沿用默认值，true 明确开启，false 明确关闭
 */
public record DocumentParseOptions(String language, Boolean recognizeTables, Boolean recognizeFormulas) {
    public DocumentParseOptions {
        if (language != null) {
            language = language.strip();
            if (language.isEmpty() || language.equalsIgnoreCase("auto")) {
                throw new IllegalArgumentException("use null for provider default, not blank or auto");
            }
            try {
                // 仅检查标签语法并规范化大小写；这里不知道具体 Provider 安装了哪些语言模型。
                Locale locale = new Locale.Builder().setLanguageTag(language).build();
                if (locale.getLanguage().isEmpty()) {
                    throw new IllegalArgumentException("language tag must identify a language");
                }
                language = locale.toLanguageTag();
            } catch (IllformedLocaleException ex) {
                throw new IllegalArgumentException("invalid language tag", ex);
            }
        }
    }

    /**
     * 创建“本次不覆盖任何配置”的选项，不在 SPI 中硬编码语言或识别开关的实际默认值。
     */
    public static DocumentParseOptions defaults() {
        return new DocumentParseOptions(null, null, null);
    }
}
