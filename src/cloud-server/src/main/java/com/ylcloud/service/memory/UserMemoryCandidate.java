package com.ylcloud.service.memory;

public record UserMemoryCandidate(String type, String key, String content, double confidence, boolean userConfirmed) {}
