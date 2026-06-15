package com.ylcloud.service.rag;

import com.ylcloud.entity.File;
import com.ylcloud.entity.SpaceFile;

public interface DocumentTextExtractor {
    /**
     * 提取空间文件对应的文档文本。
     *
     * @param spaceFile 空间文件对象
     * @param file 文件对象
     * @return 文档文本提取结果
     */
    ExtractedDocumentText extract(SpaceFile spaceFile, File file);
}
