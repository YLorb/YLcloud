package com.ylcloud.service.rag;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExtractedDocumentText {
    private boolean success;
    private boolean fallback;
    private String text;
    private String parser;
    private String errorMessage;

    /**
     * 执行 success 函数的业务处理。
     *
     * @param text 文本内容
     * @param parser 方法入参
     * @return 处理结果
     */
    public static ExtractedDocumentText success(String text, String parser) {
        return new ExtractedDocumentText(true,false,text,parser,null);
    }

    /**
     * 执行 fallback 函数的业务处理。
     *
     * @param message 方法入参
     * @return 处理结果
     */
    public static ExtractedDocumentText fallback(String message) {
        return new ExtractedDocumentText(false,true,null,"metadata",message);
    }
}
