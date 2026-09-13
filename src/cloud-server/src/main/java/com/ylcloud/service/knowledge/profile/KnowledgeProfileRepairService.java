package com.ylcloud.service.knowledge.profile;

import com.ylcloud.entity.FileRagChunk;
import com.ylcloud.entity.SpaceRagDocument;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class KnowledgeProfileRepairService {
    public KnowledgeProfileDraft repair(KnowledgeProfileDraft profile, SpaceRagDocument document, List<FileRagChunk> chunks) {
        KnowledgeProfileDraft repaired = new KnowledgeProfileDraft();
        repaired.setTitle(blankToDefault(profile.getTitle(),document.getFileName()));
        repaired.setSummary(blankToDefault(profile.getSummary(),fallbackSummary(chunks)));
        repaired.setKeywords(profile.getKeywords() == null ? new ArrayList<>() : new ArrayList<>(profile.getKeywords()));
        repaired.setTags(profile.getTags() == null ? new ArrayList<>() : new ArrayList<>(profile.getTags()));
        if(repaired.getTags().isEmpty()) {
            repaired.setTags(repaired.getKeywords().stream().limit(5).toList());
        }
        repaired.setCategory(blankToDefault(profile.getCategory(),"uncategorized"));
        repaired.setLanguage(blankToDefault(profile.getLanguage(),"unknown"));
        repaired.setDocumentType(blankToDefault(profile.getDocumentType(),blankToDefault(document.getFileType(),"document")));
        repaired.setQuestions(profile.getQuestions() == null ? new ArrayList<>() : new ArrayList<>(profile.getQuestions()));
        if(repaired.getQuestions().isEmpty()) {
            repaired.setQuestions(List.of("What is the main content of " + repaired.getTitle() + "?"));
        }
        repaired.setSchemaValid(profile.isSchemaValid());
        return repaired;
    }

    public boolean shouldRepair(KnowledgeProfileValidationResult validationResult) {
        return validationResult.getIssues().stream().anyMatch(issue -> issue.repairable()
                && ("ERROR".equals(issue.severity()) || "WARNING".equals(issue.severity())));
    }

    private String fallbackSummary(List<FileRagChunk> chunks) {
        for(FileRagChunk chunk : chunks == null ? List.<FileRagChunk>of() : chunks) {
            if(chunk.getContent() != null && !chunk.getContent().isBlank()) {
                String content = chunk.getContent().trim();
                return content.substring(0,Math.min(content.length(),300));
            }
        }
        return "No usable content summary generated.";
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
