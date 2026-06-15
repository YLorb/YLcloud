package com.ylcloud.service.rag;

import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.File;
import com.ylcloud.entity.SpaceFile;
import com.ylcloud.utils.MinioclientUtil;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Service
public class DefaultDocumentTextExtractor implements DocumentTextExtractor {
    private final MinioclientUtil minioclientUtil;
    private final RagProperties ragProperties;
    private final Tika tika = new Tika();

    /**
     * 初始化 DefaultDocumentTextExtractor 对象。
     *
     * @param minioclientUtil 方法入参
     * @param ragProperties RAG 配置属性
     */
    public DefaultDocumentTextExtractor(MinioclientUtil minioclientUtil, RagProperties ragProperties) {
        this.minioclientUtil = minioclientUtil;
        this.ragProperties = ragProperties;
    }

    /**
     * 提取 extract 相关逻辑。
     *
     * @param spaceFile 空间文件对象
     * @param file 文件对象
     * @return 处理结果
     */
    @Override
    public ExtractedDocumentText extract(SpaceFile spaceFile, File file) {
        RagProperties.Extraction extraction = ragProperties.getExtraction();
        if(!Boolean.TRUE.equals(extraction.getEnabled())) {
            return ExtractedDocumentText.fallback("文档正文解析未启用");
        }
        if(file == null || file.getFileUuid() == null || file.getFileUuid().isBlank()) {
            return ExtractedDocumentText.fallback("文件元数据不存在");
        }
        if(file.getSize() != null && extraction.getMaxFileSize() != null && file.getSize() > extraction.getMaxFileSize()) {
            return ExtractedDocumentText.fallback("文件超过正文解析大小限制");
        }
        String extension = extension(spaceFile == null ? null : spaceFile.getFileName(),file.getName());
        if(!isSupported(extension,extraction)) {
            return ExtractedDocumentText.fallback("暂不支持该文件类型");
        }
        try(InputStream inputStream = minioclientUtil.getObjectStream(file.getFileUuid())) {
            String text = isPlainText(extension)
                    ? readPlainText(inputStream,extraction.getMaxTextLength())
                    : parseWithTika(inputStream,extraction.getMaxTextLength());
            text = normalize(text,extraction.getMaxTextLength());
            if(text.isBlank()) {
                return ExtractedDocumentText.fallback("文档正文为空");
            }
            return ExtractedDocumentText.success(text,isPlainText(extension) ? "plain-text" : "tika");
        } catch (Exception ex) {
            return ExtractedDocumentText.fallback("文档解析失败：" + ex.getMessage());
        }
    }

    /**
     * 解析 parseWithTika 相关逻辑。
     *
     * @param inputStream 方法入参
     * @param maxTextLength 方法入参
     * @return 处理结果
     */
    private String parseWithTika(InputStream inputStream, Integer maxTextLength) throws Exception {
        int limit = safeMaxTextLength(maxTextLength);
        return tika.parseToString(inputStream,new Metadata(),limit);
    }

    /**
     * 执行 readPlainText 函数的业务处理。
     *
     * @param inputStream 方法入参
     * @param maxTextLength 方法入参
     * @return 处理结果
     */
    private String readPlainText(InputStream inputStream, Integer maxTextLength) throws Exception {
        int limit = safeMaxTextLength(maxTextLength);
        StringBuilder builder = new StringBuilder();
        try(BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while((read = reader.read(buffer)) != -1 && builder.length() < limit) {
                int appendLength = Math.min(read,limit - builder.length());
                builder.append(buffer,0,appendLength);
            }
        }
        return builder.toString();
    }

    /**
     * 规范化 normalize 相关逻辑。
     *
     * @param text 文本内容
     * @param maxTextLength 方法入参
     * @return 处理结果
     */
    private String normalize(String text, Integer maxTextLength) {
        if(text == null) {
            return "";
        }
        String normalized = text.replace('\u0000',' ')
                .replaceAll("[\\t\\x0B\\f\\r]+"," ")
                .replaceAll("\\n{3,}","\n\n")
                .trim();
        int limit = safeMaxTextLength(maxTextLength);
        return normalized.length() > limit ? normalized.substring(0,limit) : normalized;
    }

    /**
     * 执行 isSupported 函数的业务处理。
     *
     * @param extension 方法入参
     * @param extraction 方法入参
     * @return 处理结果
     */
    private boolean isSupported(String extension, RagProperties.Extraction extraction) {
        return extension != null
                && extraction.getSupportedExtensions() != null
                && extraction.getSupportedExtensions().stream()
                .anyMatch(item -> extension.equalsIgnoreCase(item));
    }

    /**
     * 执行 isPlainText 函数的业务处理。
     *
     * @param extension 方法入参
     * @return 处理结果
     */
    private boolean isPlainText(String extension) {
        return "txt".equals(extension) || "md".equals(extension) || "markdown".equals(extension);
    }

    /**
     * 执行 extension 函数的业务处理。
     *
     * @param preferredName 方法入参
     * @param fallbackName 方法入参
     * @return 处理结果
     */
    private String extension(String preferredName, String fallbackName) {
        String name = preferredName == null || preferredName.isBlank() ? fallbackName : preferredName;
        if(name == null) {
            return null;
        }
        int index = name.lastIndexOf('.');
        if(index < 0 || index == name.length() - 1) {
            return null;
        }
        return name.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 执行 safeMaxTextLength 函数的业务处理。
     *
     * @param maxTextLength 方法入参
     * @return 影响行数
     */
    private int safeMaxTextLength(Integer maxTextLength) {
        return maxTextLength == null || maxTextLength <= 0 ? 500000 : maxTextLength;
    }
}
