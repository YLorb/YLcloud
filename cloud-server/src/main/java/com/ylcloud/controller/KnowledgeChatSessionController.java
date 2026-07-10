package com.ylcloud.controller;

import com.ylcloud.DTO.KnowledgeChatMessageCreateDTO;
import com.ylcloud.DTO.KnowledgeChatSessionCreateDTO;
import com.ylcloud.DTO.KnowledgeChatSessionUpdateDTO;
import com.ylcloud.Result;
import com.ylcloud.VO.KnowledgeChatMessageVO;
import com.ylcloud.VO.KnowledgeChatSessionVO;
import com.ylcloud.context.BaseContext;
import com.ylcloud.service.KnowledgeChatSessionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge/chat/sessions")
public class KnowledgeChatSessionController {
    private final KnowledgeChatSessionService sessionService;

    public KnowledgeChatSessionController(KnowledgeChatSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @GetMapping
    public Result<List<KnowledgeChatSessionVO>> list(@RequestParam(required = false) String keyword,
                                                     @RequestParam(required = false) Integer limit) {
        return Result.success(sessionService.list(BaseContext.getCurrentId(),keyword,limit));
    }

    @PostMapping
    public Result<KnowledgeChatSessionVO> create(@RequestBody @Valid KnowledgeChatSessionCreateDTO dto) {
        return Result.success(sessionService.create(BaseContext.getCurrentId(),dto));
    }

    @GetMapping("/{sessionId}")
    public Result<KnowledgeChatSessionVO> detail(@PathVariable Long sessionId) {
        return Result.success(sessionService.detail(BaseContext.getCurrentId(),sessionId));
    }

    @PutMapping("/{sessionId}")
    public Result<KnowledgeChatSessionVO> updateTitle(@PathVariable Long sessionId,
                                                      @RequestBody @Valid KnowledgeChatSessionUpdateDTO dto) {
        return Result.success(sessionService.updateTitle(BaseContext.getCurrentId(),sessionId,dto));
    }

    @DeleteMapping("/{sessionId}")
    public Result<Boolean> delete(@PathVariable Long sessionId) {
        return Result.success(sessionService.delete(BaseContext.getCurrentId(),sessionId));
    }

    @PostMapping("/{sessionId}/messages")
    public Result<KnowledgeChatMessageVO> appendMessage(@PathVariable Long sessionId,
                                                        @RequestBody @Valid KnowledgeChatMessageCreateDTO dto) {
        return Result.success(sessionService.appendMessage(BaseContext.getCurrentId(),sessionId,dto));
    }
}
