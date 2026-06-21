package com.ylcloud.service.rag.parser;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class StructuredChunk {
    private Integer chunkIndex;
    private String chunkType;
    private Integer parentChunkIndex;
    private String parentContentHash;
    private Integer chunkLevel;
    private String content;
    private String contentHash;
    private Integer tokenCount;
    private Integer pageStart;
    private Integer pageEnd;
    private List<String> headingPath = new ArrayList<>();
    private List<String> blockTypes = new ArrayList<>();
    private String metadataJson;
}
