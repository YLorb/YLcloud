package com.ylcloud.service.rag;

import com.ylcloud.entity.File;
import com.ylcloud.entity.SpaceFile;

public interface DocumentTextExtractor {
    ExtractedDocumentText extract(SpaceFile spaceFile, File file);
}
