package com.ylcloud.service.knowledge.pipeline;

import com.ylcloud.entity.FileRagChunk;
import com.ylcloud.entity.SpaceRagDocument;
import com.ylcloud.service.knowledge.profile.KnowledgeProfileDraft;
import com.ylcloud.service.knowledge.profile.KnowledgeProfileValidationResult;
import com.ylcloud.service.knowledge.quality.KnowledgeProfileQualityResult;
import lombok.Data;

import java.util.List;

@Data
public class KnowledgePipelineContext {
    private Long taskId;
    private Long spaceId;
    private Long documentId;
    private int attemptNo = 1;
    private String traceId;
    private boolean forceRebuild;
    private SpaceRagDocument document;
    private List<FileRagChunk> chunks = List.of();
    private String contentContext;
    private String rawLlmOutput;
    private KnowledgeProfileDraft profile;
    private KnowledgeProfileValidationResult validationResult;
    private KnowledgeProfileQualityResult scoreBeforeRepair;
    private KnowledgeProfileQualityResult scoreAfterRepair;
    private int repairAttempt;
    private String repairReason;
}
