package com.ylcloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgePipelineServiceTest {
    private final KnowledgePipelineService service = new KnowledgePipelineService(
            null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,new ObjectMapper()
    );

    @Test
    void parsesJsonObjectFromModelText() {
        String text = """
                ```json
                {
                  "title": "RAG Guide",
                  "summary": "A short guide.",
                  "keywords": ["rag", "qdrant"],
                  "tags": ["knowledge", "search"],
                  "category": "engineering",
                  "language": "en",
                  "documentType": "guide",
                  "questions": ["How does retrieval work?"]
                }
                ```
                """;

        KnowledgePipelineService.GeneratedProfile profile = service.parseGeneratedProfile(text);

        assertEquals("RAG Guide",profile.title());
        assertEquals(List.of("rag","qdrant"),profile.keywords());
        assertEquals(List.of("knowledge","search"),profile.tags());
        assertEquals("engineering",profile.category());
        assertEquals(List.of("How does retrieval work?"),profile.questions());
    }

    @Test
    void badModelJsonReturnsEmptyProfile() {
        KnowledgePipelineService.GeneratedProfile profile = service.parseGeneratedProfile("not-json");

        assertTrue(profile.keywords().isEmpty());
        assertTrue(profile.tags().isEmpty());
        assertTrue(profile.questions().isEmpty());
    }
}
