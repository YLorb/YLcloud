package com.ylcloud.service.knowledge.profile;

import com.ylcloud.service.knowledge.quality.QualityIssue;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class KnowledgeProfileValidationResult {
    private boolean schemaValid = true;
    private List<QualityIssue> issues = new ArrayList<>();
}
