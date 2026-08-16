package com.ylcloud.service.knowledge.quality;

import com.ylcloud.constant.SpaceConstant;
import com.ylcloud.entity.FileRagChunk;
import com.ylcloud.service.knowledge.profile.KnowledgeProfileDraft;
import com.ylcloud.service.knowledge.profile.KnowledgeProfileValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KnowledgeProfileQualityLifecycleTest {
    @Test
    void scoreAtConfiguredBoundaryActivatesValidAsset() {
        KnowledgeProfileQualityService service = new KnowledgeProfileQualityService();
        service.setAutoActivateThreshold(100);

        KnowledgeProfileQualityResult result = service.evaluate(goodDraft(true),validation(true),List.of(chunk()),1);

        assertEquals(100,result.getTotalScore());
        assertEquals(SpaceConstant.KNOWLEDGE_PROFILE_VALID,result.getProfileStatus());
    }

    @Test
    void structuralFailureAlwaysNeedsReviewEvenWhenContentQualityIsHigh() {
        KnowledgeProfileQualityService service = new KnowledgeProfileQualityService();
        service.setAutoActivateThreshold(50);

        KnowledgeProfileQualityResult result = service.evaluate(goodDraft(false),validation(false),List.of(chunk()),1);

        assertEquals(SpaceConstant.KNOWLEDGE_PROFILE_NEEDS_REVIEW,result.getProfileStatus());
    }

    @Test
    void lowConfidenceResultNeedsReviewAndThresholdCanBeRolledBack() {
        KnowledgeProfileQualityService service = new KnowledgeProfileQualityService();
        KnowledgeProfileDraft draft = goodDraft(true);
        draft.setSummary("short");
        draft.setCategory("uncategorized");
        draft.setTags(List.of("one"));
        draft.setKeywords(List.of("one"));

        service.setAutoActivateThreshold(75);
        assertEquals(SpaceConstant.KNOWLEDGE_PROFILE_NEEDS_REVIEW,
                service.evaluate(draft,validation(true),List.of(chunk()),1).getProfileStatus());
        service.setAutoActivateThreshold(60);
        assertEquals(SpaceConstant.KNOWLEDGE_PROFILE_VALID,
                service.evaluate(draft,validation(true),List.of(chunk()),1).getProfileStatus());
    }

    private KnowledgeProfileDraft goodDraft(boolean schemaValid) {
        KnowledgeProfileDraft draft = new KnowledgeProfileDraft();
        draft.setSchemaValid(schemaValid);
        draft.setSummary("This is a sufficiently long and validated summary for the generated knowledge asset lifecycle.");
        draft.setCategory("engineering");
        draft.setTags(List.of("java","architecture"));
        draft.setKeywords(List.of("lifecycle","quality"));
        draft.setQuestions(List.of("How is the asset activated?"));
        return draft;
    }

    private KnowledgeProfileValidationResult validation(boolean valid) {
        KnowledgeProfileValidationResult result = new KnowledgeProfileValidationResult();
        result.setSchemaValid(valid);
        if(!valid) result.getIssues().add(new QualityIssue("schema","PROFILE_SCHEMA_INVALID","ERROR","invalid",false));
        return result;
    }

    private FileRagChunk chunk() {
        FileRagChunk chunk = new FileRagChunk();
        chunk.setContent("knowledge content");
        chunk.setMetadata("{}");
        return chunk;
    }
}
