package com.ylcloud.service.knowledge.quality;

import com.ylcloud.constant.SpaceConstant;
import com.ylcloud.entity.FileRagChunk;
import com.ylcloud.service.knowledge.profile.KnowledgeProfileDraft;
import com.ylcloud.service.knowledge.profile.KnowledgeProfileValidationResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class KnowledgeProfileQualityService {
    public KnowledgeProfileQualityResult evaluate(KnowledgeProfileDraft profile,
                                                  KnowledgeProfileValidationResult validationResult,
                                                  List<FileRagChunk> usedChunks,
                                                  int totalContentChunkCount) {
        KnowledgeProfileQualityResult result = new KnowledgeProfileQualityResult();
        List<QualityIssue> issues = new ArrayList<>(validationResult.getIssues());
        int summary = summaryScore(profile,issues);
        int category = categoryScore(profile,issues);
        int tags = listScore(profile.getTags(),15,2,8,"tags","TAGS_LOW_QUALITY",issues);
        int keywords = listScore(profile.getKeywords(),10,2,12,"keywords","KEYWORDS_LOW_QUALITY",issues);
        int questions = listScore(profile.getQuestions(),15,1,8,"questions","QUESTIONS_LOW_QUALITY",issues);
        int coverage = chunkCoverageScore(usedChunks,totalContentChunkCount,issues);
        int structure = validationResult.isSchemaValid() ? 10 : 0;
        result.getDimensionScores().put("summary",summary);
        result.getDimensionScores().put("category",category);
        result.getDimensionScores().put("tags",tags);
        result.getDimensionScores().put("keywords",keywords);
        result.getDimensionScores().put("questions",questions);
        result.getDimensionScores().put("chunkCoverage",coverage);
        result.getDimensionScores().put("structure",structure);
        int total = summary + category + tags + keywords + questions + coverage + structure;
        result.setTotalScore(total);
        result.setProfileStatus(total >= 75 ? SpaceConstant.KNOWLEDGE_PROFILE_VALID : SpaceConstant.KNOWLEDGE_PROFILE_NEEDS_REVIEW);
        result.setIssues(issues);
        return result;
    }

    private int summaryScore(KnowledgeProfileDraft profile, List<QualityIssue> issues) {
        String summary = profile.getSummary();
        if(summary == null || summary.isBlank()) {
            issues.add(issue("summary","SUMMARY_EMPTY","ERROR","Summary is empty",true));
            return 0;
        }
        if(summary.trim().length() < 50) {
            issues.add(issue("summary","SUMMARY_TOO_SHORT","WARNING","Summary is shorter than 50 characters",true));
            return 10;
        }
        return 20;
    }

    private int categoryScore(KnowledgeProfileDraft profile, List<QualityIssue> issues) {
        String category = profile.getCategory();
        if(category == null || category.isBlank() || "uncategorized".equalsIgnoreCase(category)) {
            issues.add(issue("category","CATEGORY_GENERIC","WARNING","Category is generic or missing",true));
            return 8;
        }
        return 15;
    }

    private int listScore(List<String> values, int maxScore, int minGood, int maxGood, String field, String code, List<QualityIssue> issues) {
        int size = values == null ? 0 : values.size();
        if(size == 0) {
            issues.add(issue(field,field.toUpperCase() + "_EMPTY","WARNING",field + " are empty",true));
            return 0;
        }
        if(size < minGood) {
            issues.add(issue(field,code,"WARNING",field + " count is low",true));
            return Math.max(1,maxScore / 2);
        }
        return size > maxGood ? maxScore - 2 : maxScore;
    }

    private int chunkCoverageScore(List<FileRagChunk> usedChunks, int totalContentChunkCount, List<QualityIssue> issues) {
        int used = usedChunks == null ? 0 : (int) usedChunks.stream().filter(this::isContentChunk).count();
        if(totalContentChunkCount <= 0) {
            issues.add(issue("chunkCoverage","NO_CONTENT_CHUNK","ERROR","No content chunk is available",false));
            return 0;
        }
        double ratio = used * 1.0 / totalContentChunkCount;
        if(ratio >= 0.5) {
            return 15;
        }
        if(ratio >= 0.2) {
            issues.add(issue("chunkCoverage","LOW_CHUNK_COVERAGE","WARNING","Used content chunk ratio is below 50%",false));
            return 10;
        }
        issues.add(issue("chunkCoverage","VERY_LOW_CHUNK_COVERAGE","ERROR","Used content chunk ratio is below 20%",false));
        return 5;
    }

    private boolean isContentChunk(FileRagChunk chunk) {
        if(chunk == null || chunk.getContent() == null || chunk.getContent().isBlank()) {
            return false;
        }
        String metadata = chunk.getMetadata() == null ? "" : chunk.getMetadata().toLowerCase();
        return !metadata.contains("metadata_only")
                && !metadata.contains("metadata-only")
                && !metadata.contains("\"parser\":\"metadata\"")
                && !metadata.contains("\"parser\": \"metadata\"");
    }

    private QualityIssue issue(String field, String code, String severity, String message, boolean repairable) {
        return new QualityIssue(field,code,severity,message,repairable);
    }
}
