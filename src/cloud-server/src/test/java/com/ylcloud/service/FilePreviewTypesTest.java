package com.ylcloud.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FilePreviewTypesTest {
    /**
     * 验证个人文件与空间文件共用的扩展名和预览规则。
     */
    @Test
    void resolvesCommonPreviewTypes() {
        assertEquals("image/jpeg", FilePreviewTypes.resolveContentType("PHOTO.JPG", null));
        assertEquals("image", FilePreviewTypes.resolvePreviewType("image/jpeg", "PHOTO.JPG"));
        assertEquals("application/pdf", FilePreviewTypes.resolveContentType("report.pdf", null));
        assertEquals("pdf", FilePreviewTypes.resolvePreviewType("application/pdf", "report.pdf"));
        assertEquals("text", FilePreviewTypes.resolvePreviewType("application/octet-stream", "notes.md"));
        assertEquals("application/json;charset=UTF-8", FilePreviewTypes.resolveContentType("data.json", null));
        assertEquals("office", FilePreviewTypes.resolvePreviewType("application/octet-stream", "report.DOCX"));
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", FilePreviewTypes.resolveContentType("budget.xlsx", null));
        assertEquals("office", FilePreviewTypes.resolvePreviewType("application/vnd.ms-powerpoint", "slides.ppt"));
    }

    /**
     * 验证未知类型和仅有存储类型时的回退行为。
     */
    @Test
    void preservesFallbackBehavior() {
        assertEquals("audio/mpeg", FilePreviewTypes.resolveContentType(null, ".mp3"));
        assertEquals("application/octet-stream", FilePreviewTypes.resolveContentType("archive.zip", ".pdf"));
        assertEquals("unsupported", FilePreviewTypes.resolvePreviewType("application/octet-stream", "archive.zip"));
    }
}
