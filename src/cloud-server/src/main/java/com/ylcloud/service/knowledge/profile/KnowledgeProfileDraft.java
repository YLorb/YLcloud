package com.ylcloud.service.knowledge.profile;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class KnowledgeProfileDraft {
    private String title;
    private String summary;
    private List<String> keywords = new ArrayList<>();
    private List<String> tags = new ArrayList<>();
    private String category;
    private String language;
    private String documentType;
    private List<String> questions = new ArrayList<>();
    private boolean schemaValid;
}
