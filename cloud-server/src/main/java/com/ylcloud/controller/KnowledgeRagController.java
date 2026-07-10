package com.ylcloud.controller;

import com.ylcloud.DTO.KnowledgeRagQueryDTO;
import com.ylcloud.Result;
import com.ylcloud.VO.KnowledgeRagQueryVO;
import com.ylcloud.context.BaseContext;
import com.ylcloud.service.KnowledgeRagQueryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge/rag")
public class KnowledgeRagController {
    private final KnowledgeRagQueryService knowledgeRagQueryService;

    public KnowledgeRagController(KnowledgeRagQueryService knowledgeRagQueryService) {
        this.knowledgeRagQueryService = knowledgeRagQueryService;
    }

    @PostMapping("/query")
    public Result<KnowledgeRagQueryVO> query(@RequestBody @Valid KnowledgeRagQueryDTO dto) {
        return Result.success(knowledgeRagQueryService.query(dto,BaseContext.getCurrentId()));
    }
}
