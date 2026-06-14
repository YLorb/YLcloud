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

    public DefaultDocumentTextExtractor(MinioclientUtil minioclientUtil, RagProperties ragProperties) {
        this.minioclientUtil = minioclientUtil;
        this.ragProperties = ragProperties;
    }

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

    private String parseWithTika(InputStream inputStream, Integer maxTextLength) throws Exception {
        int limit = safeMaxTextLength(maxTextLength);
        return tika.parseToString(inputStream,new Metadata(),limit);
    }

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

    private boolean isSupported(String extension, RagProperties.Extraction extraction) {
        return extension != null
                && extraction.getSupportedExtensions() != null
                && extraction.getSupportedExtensions().stream()
                .anyMatch(item -> extension.equalsIgnoreCase(item));
    }

    private boolean isPlainText(String extension) {
        return "txt".equals(extension) || "md".equals(extension) || "markdown".equals(extension);
    }

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

    private int safeMaxTextLength(Integer maxTextLength) {
        return maxTextLength == null || maxTextLength <= 0 ? 500000 : maxTextLength;
    }
}
