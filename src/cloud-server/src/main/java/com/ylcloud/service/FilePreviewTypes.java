package com.ylcloud.service;

/**
 * 统一处理个人文件和空间文件的浏览器预览类型。
 */
public final class FilePreviewTypes {
    private FilePreviewTypes() {
    }

    /**
     * 解析文件预览类型。
     *
     * @param contentType 文件的 MIME 类型
     * @param fileName 文件名
     * @return 图片、PDF、视频、音频、文本或不支持
     */
    public static String resolvePreviewType(String contentType, String fileName) {
        if(hasExtension(fileName,".doc",".docx",".xls",".xlsx",".ppt",".pptx")) return "office";
        if(contentType.startsWith("image/")) return "image";
        if("application/pdf".equals(contentType)) return "pdf";
        if(contentType.startsWith("video/")) return "video";
        if(contentType.startsWith("audio/")) return "audio";
        if(contentType.startsWith("text/") || hasExtension(fileName,".md",".json",".xml",".csv",".log",".java",".js",".ts",".html",".css",".sql",".yml",".yaml")) {
            return "text";
        }
        return "unsupported";
    }

    /**
     * 推断文件类型，指导浏览器正确预览/打开。
     *
     * @param fileName 文件名
     * @param storedType 存储的文件类型
     * @return 浏览器使用的 MIME 类型
     */
    public static String resolveContentType(String fileName, String storedType) {
        String lowerName = fileName == null ? "" : fileName.toLowerCase();
        String lowerType = storedType == null ? "" : storedType.toLowerCase();
        String key = lowerName.isEmpty() ? lowerType : lowerName;
        if(hasExtension(key,".jpg",".jpeg")) return "image/jpeg";
        if(hasExtension(key,".png")) return "image/png";
        if(hasExtension(key,".gif")) return "image/gif";
        if(hasExtension(key,".webp")) return "image/webp";
        if(hasExtension(key,".bmp")) return "image/bmp";
        if(hasExtension(key,".svg")) return "image/svg+xml";
        if(hasExtension(key,".pdf")) return "application/pdf";
        if(hasExtension(key,".doc")) return "application/msword";
        if(hasExtension(key,".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if(hasExtension(key,".xls")) return "application/vnd.ms-excel";
        if(hasExtension(key,".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if(hasExtension(key,".ppt")) return "application/vnd.ms-powerpoint";
        if(hasExtension(key,".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if(hasExtension(key,".mp4")) return "video/mp4";
        if(hasExtension(key,".webm")) return "video/webm";
        if(hasExtension(key,".ogg",".ogv")) return "video/ogg";
        if(hasExtension(key,".mp3")) return "audio/mpeg";
        if(hasExtension(key,".wav")) return "audio/wav";
        if(hasExtension(key,".m4a")) return "audio/mp4";
        if(hasExtension(key,".flac")) return "audio/flac";
        if(hasExtension(key,".txt",".md",".log",".csv",".java",".js",".ts",".html",".css",".sql",".yml",".yaml")) return "text/plain;charset=UTF-8";
        if(hasExtension(key,".json")) return "application/json;charset=UTF-8";
        if(hasExtension(key,".xml")) return "application/xml;charset=UTF-8";
        return "application/octet-stream";
    }

    /**
     * 判断文件名是否以指定扩展名结尾。
     *
     * @param fileName 文件名
     * @param extensions 扩展名列表
     * @return 是否匹配
     */
    private static boolean hasExtension(String fileName, String... extensions) {
        // String... extensions 是 Java 的可变参数写法，表示这个方法可以接收任意数量的 String 参数。
        if(fileName == null) return false;
        String lower = fileName.toLowerCase();
        for(String extension : extensions) {
            if(lower.endsWith(extension)) return true;
        }
        return false;
    }
}
