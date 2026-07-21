package com.ylcloud.controller;

import com.ylcloud.DTO.KnowledgeChatMessageCreateDTO;
import com.ylcloud.DTO.KnowledgeChatQueryCreateDTO;
import com.ylcloud.DTO.KnowledgeChatSessionCreateDTO;
import com.ylcloud.DTO.KnowledgeChatSessionScopeUpdateDTO;
import com.ylcloud.DTO.KnowledgeChatSessionUpdateDTO;
import com.ylcloud.DTO.KnowledgeChatFeedbackDTO;
import com.ylcloud.Result;
import com.ylcloud.VO.KnowledgeChatMessageVO;
import com.ylcloud.VO.KnowledgeChatSessionVO;
import com.ylcloud.VO.KnowledgeChatEpisodeVO;
import com.ylcloud.context.BaseContext;
import com.ylcloud.service.KnowledgeChatSessionService;
import com.ylcloud.service.KnowledgeChatQueryService;
import com.ylcloud.service.KnowledgeChatEpisodeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge/chat/sessions")
public class KnowledgeChatSessionController {
    private final KnowledgeChatSessionService sessionService;
    private final KnowledgeChatQueryService queryService;
    private final KnowledgeChatEpisodeService episodeService;

    public KnowledgeChatSessionController(KnowledgeChatSessionService sessionService,
                                          KnowledgeChatQueryService queryService,
                                          KnowledgeChatEpisodeService episodeService) {
        this.sessionService = sessionService;
        this.queryService = queryService;
        this.episodeService = episodeService;
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

    @PutMapping("/{sessionId}/scope")
    public Result<KnowledgeChatSessionVO> updateScope(@PathVariable Long sessionId,
                                                      @RequestBody @Valid KnowledgeChatSessionScopeUpdateDTO dto) {
        return Result.success(sessionService.updateScope(BaseContext.getCurrentId(),sessionId,dto));
    }

    @DeleteMapping("/{sessionId}")
    public Result<Boolean> delete(@PathVariable Long sessionId) {
        Long userId=BaseContext.getCurrentId();
        Boolean deleted=sessionService.delete(userId,sessionId);
        episodeService.delete(userId,sessionId);
        return Result.success(deleted);
    }

    @PostMapping("/{sessionId}/messages")
    public Result<KnowledgeChatMessageVO> appendMessage(@PathVariable Long sessionId,
                                                        @RequestBody @Valid KnowledgeChatMessageCreateDTO dto) {
        return Result.success(sessionService.appendMessage(BaseContext.getCurrentId(),sessionId,dto));
    }

    @PostMapping("/{sessionId}/queries")
    public Result<KnowledgeChatMessageVO> submitQuery(@PathVariable Long sessionId,
                                                      @RequestBody @Valid KnowledgeChatQueryCreateDTO dto,
                                                      @RequestHeader(value = "Idempotency-Key", required = false) String requestKey) {
        return Result.success(queryService.submit(sessionId,BaseContext.getCurrentId(),dto,requestKey));
    }

    @PostMapping("/{sessionId}/queries/{messageId}/retry")
    public Result<KnowledgeChatMessageVO> retryQuery(@PathVariable Long sessionId, @PathVariable Long messageId) {
        return Result.success(queryService.retry(sessionId,messageId,BaseContext.getCurrentId()));
    }

    @PostMapping("/{sessionId}/queries/{messageId}/cancel")
    public Result<KnowledgeChatMessageVO> cancelQuery(@PathVariable Long sessionId, @PathVariable Long messageId) {
        return Result.success(queryService.cancel(sessionId,messageId,BaseContext.getCurrentId()));
    }

    @GetMapping("/{sessionId}/episodes")
    public Result<List<KnowledgeChatEpisodeVO>> episodes(@PathVariable Long sessionId) {
        return Result.success(episodeService.list(BaseContext.getCurrentId(),sessionId));
    }

    @PutMapping("/{sessionId}/messages/{messageId}/feedback")
    public Result<Boolean> feedback(@PathVariable Long sessionId,@PathVariable Long messageId,
                                    @RequestBody @Valid KnowledgeChatFeedbackDTO dto) {
        episodeService.feedback(BaseContext.getCurrentId(),sessionId,messageId,dto);
        return Result.success(true);
    }
}
