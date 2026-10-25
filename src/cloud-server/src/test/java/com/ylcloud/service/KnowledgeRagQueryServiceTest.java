package com.ylcloud.service;

import com.ylcloud.DTO.KnowledgeRagQueryDTO;
import com.ylcloud.VO.*;
import com.ylcloud.entity.Space;
import com.ylcloud.mapper.SpaceMapper;
import com.ylcloud.service.space.SpaceRagService;
import com.ylcloud.service.rag.RagEvidenceService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeRagQueryServiceTest {
    @Test void globalReferenceNumbersAreRenderedFromStableEvidenceIds() {
        SpaceMapper spaces=mock(SpaceMapper.class);
        SpaceRagService rag=mock(SpaceRagService.class);
        RagEvidenceService evidence=mock(RagEvidenceService.class);
        KnowledgeRagQueryService service=new KnowledgeRagQueryService(rag,spaces);
        service.setEvidenceService(evidence);
        for(long id:List.of(1L,2L)) {
            Space space=new Space(); space.setId(id); space.setName("KB"+id); when(spaces.getById(id)).thenReturn(space);
            SpaceRagCitationVO citation=new SpaceRagCitationVO(); citation.setIndex(1); citation.setSourceEvidenceId("source-"+id);
            RagAnswerClaim claim=new RagAnswerClaim(); claim.setText("text"+id); claim.setType("FACT");
            claim.setEvidenceIds(List.of("source-"+id)); claim.setConditions(List.of());
            SpaceRagQueryVO part=new SpaceRagQueryVO(); part.setAnswer("old[1]"); part.setAnswerStatus("ANSWERED"); part.setCitations(List.of(citation)); part.setClaims(List.of(claim));
            when(rag.query(eq(id),any(),eq(7L))).thenReturn(part);
        }
        when(evidence.storeAnswer(eq(7L),anyList(),anyString(),anyList(),eq("ANSWERED"))).thenReturn("aggregate-answer");
        KnowledgeRagQueryDTO dto=new KnowledgeRagQueryDTO(); dto.setSpaceIds(List.of(1L,2L)); dto.setQuestion("question");
        KnowledgeRagQueryVO result=service.query(dto,7L);
        assertTrue(result.getAnswer().contains("text1[1]"));
        assertTrue(result.getAnswer().contains("text2[2]"));
        assertEquals("aggregate-answer",result.getAnswerId());
    }
}
