package com.ylcloud.service.knowledge.profile;

import com.ylcloud.service.knowledge.quality.QualityIssue;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeProfileValidator {
    public KnowledgeProfileValidationResult validate(KnowledgeProfileDraft profile) {
        KnowledgeProfileValidationResult result = new KnowledgeProfileValidationResult();
        if(!profile.isSchemaValid()) {
            result.setSchemaValid(false);
            result.getIssues().add(issue("schema","PROFILE_SCHEMA_INVALID","ERROR","Profile JSON schema is invalid",false));
        }
        if(profile.getSummary() == null || profile.getSummary().isBlank()) {
            result.getIssues().add(issue("summary","SUMMARY_EMPTY","ERROR","Summary is empty",true));
        }
        if(profile.getCategory() == null || profile.getCategory().isBlank()) {
            result.getIssues().add(issue("category","CATEGORY_EMPTY","WARNING","Category is empty",true));
        }
        if(profile.getTags() == null || profile.getTags().isEmpty()) {
            result.getIssues().add(issue("tags","TAGS_EMPTY","WARNING","Tags are empty",true));
        }
        if(profile.getQuestions() == null || profile.getQuestions().isEmpty()) {
            result.getIssues().add(issue("questions","QUESTIONS_EMPTY","WARNING","Generated questions are empty",true));
        }
        return result;
    }

    private QualityIssue issue(String field, String code, String severity, String message, boolean repairable) {
        return new QualityIssue(field,code,severity,message,repairable);
    }
}
