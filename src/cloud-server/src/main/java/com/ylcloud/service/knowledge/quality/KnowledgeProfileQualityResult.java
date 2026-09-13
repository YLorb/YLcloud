package com.ylcloud.service.knowledge.quality;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class KnowledgeProfileQualityResult {
    private int totalScore;
    private String profileStatus;
    private List<QualityIssue> issues = new ArrayList<>();
    private Map<String, Integer> dimensionScores = new LinkedHashMap<>();
}
