package com.ylcloud.controller;

import com.ylcloud.Result;
import com.ylcloud.VO.KnowledgeRetentionImpactVO;
import com.ylcloud.context.BaseContext;
import com.ylcloud.service.KnowledgeRetentionService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/space/{spaceId}/rag")
public class KnowledgeRetentionController {
    private final KnowledgeRetentionService service;
    public KnowledgeRetentionController(KnowledgeRetentionService service) { this.service=service; }

    @GetMapping("/retention-impact")
    public Result<KnowledgeRetentionImpactVO> preview(@PathVariable Long spaceId,
            @RequestParam boolean historicalEvidenceEnabled,
            @RequestParam(required=false) Integer historyRetentionDays) {
        return Result.success(service.preview(spaceId,BaseContext.getCurrentId(),historicalEvidenceEnabled,historyRetentionDays));
    }
}
