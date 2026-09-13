package com.ylcloud.service.knowledge.profile;

import com.ylcloud.entity.SpaceRagDocument;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Service
public class KnowledgeProfileNormalizer {
    public KnowledgeProfileDraft normalize(KnowledgeProfileDraft profile, SpaceRagDocument document) {
        KnowledgeProfileDraft normalized = new KnowledgeProfileDraft();
        normalized.setTitle(blankToDefault(trim(profile.getTitle()),document.getFileName()));
        normalized.setSummary(trim(profile.getSummary()));
        normalized.setKeywords(clean(profile.getKeywords(),12,2));
        normalized.setTags(clean(profile.getTags(),8,2));
        normalized.setCategory(blankToDefault(trim(profile.getCategory()),"uncategorized"));
        normalized.setLanguage(blankToDefault(trim(profile.getLanguage()),"unknown"));
        normalized.setDocumentType(blankToDefault(trim(profile.getDocumentType()),blankToDefault(document.getFileType(),"document")));
        normalized.setQuestions(clean(profile.getQuestions(),8,6));
        normalized.setSchemaValid(profile.isSchemaValid());
        return normalized;
    }

    private List<String> clean(List<String> values, int limit, int minLength) {
        LinkedHashSet<String> deduped = new LinkedHashSet<>();
        for(String value : values == null ? List.<String>of() : values) {
            String item = trim(value);
            if(item != null && item.length() >= minLength) {
                deduped.add(item);
            }
            if(deduped.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(deduped);
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
