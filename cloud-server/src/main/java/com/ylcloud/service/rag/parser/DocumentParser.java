package com.ylcloud.service.rag.parser;

import com.ylcloud.entity.File;
import com.ylcloud.entity.SpaceFile;

public interface DocumentParser {
    ParsedDocument parse(SpaceFile spaceFile, File file);
}
