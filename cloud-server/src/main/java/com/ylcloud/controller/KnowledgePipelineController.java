package com.ylcloud.controller;

import com.ylcloud.Result;
import com.ylcloud.DTO.SpaceKnowledgeProfileUpdateDTO;
import com.ylcloud.VO.SpaceKnowledgeDashboardVO;
import com.ylcloud.VO.SpaceKnowledgeDocumentItemVO;
import com.ylcloud.VO.SpaceKnowledgeDocumentProfileVO;
import com.ylcloud.VO.SpaceKnowledgeFacetVO;
import com.ylcloud.VO.SpaceKnowledgePipelineEventVO;
import com.ylcloud.VO.SpaceKnowledgePipelineTaskVO;
import com.ylcloud.VO.SpaceKnowledgeProfileDiffVO;
import com.ylcloud.VO.SpaceKnowledgeProfileVersionVO;
import com.ylcloud.context.BaseContext;
import com.ylcloud.entity.SpaceKnowledgePipelineEvent;
import com.ylcloud.service.knowledge.event.PipelineEventService;
import com.ylcloud.service.KnowledgePipelineExecutorService;
import com.ylcloud.service.KnowledgePipelineService;
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
@RequestMapping("/api/space/{spaceId}/knowledge")
public class KnowledgePipelineController {
    private final KnowledgePipelineService knowledgePipelineService;
    private final KnowledgePipelineExecutorService knowledgePipelineExecutorService;
    private final PipelineEventService pipelineEventService;

    public KnowledgePipelineController(KnowledgePipelineService knowledgePipelineService,
                                       KnowledgePipelineExecutorService knowledgePipelineExecutorService,
                                       PipelineEventService pipelineEventService) {
        this.knowledgePipelineService = knowledgePipelineService;
        this.knowledgePipelineExecutorService = knowledgePipelineExecutorService;
        this.pipelineEventService = pipelineEventService;
    }

    @PostMapping("/pipeline/documents/{documentId}/run")
    public Result<SpaceKnowledgePipelineTaskVO> runDocument(@PathVariable Long spaceId,
                                                            @PathVariable Long documentId) {
        SpaceKnowledgePipelineTaskVO task = knowledgePipelineService.submitDocument(spaceId,documentId,BaseContext.getCurrentId());
        knowledgePipelineExecutorService.runDocumentTask(task.getId());
        return Result.success(task);
    }

    @PostMapping("/pipeline/run-all")
    public Result<SpaceKnowledgePipelineTaskVO> runSpace(@PathVariable Long spaceId) {
        SpaceKnowledgePipelineTaskVO task = knowledgePipelineService.submitSpace(spaceId,BaseContext.getCurrentId());
        knowledgePipelineExecutorService.runSpaceTask(task.getId());
        return Result.success(task);
    }

    @PostMapping("/pipeline/tasks/{taskId}/retry")
    public Result<SpaceKnowledgePipelineTaskVO> retryTask(@PathVariable Long spaceId,
                                                          @PathVariable Long taskId) {
        SpaceKnowledgePipelineTaskVO task = knowledgePipelineService.retryTask(spaceId,taskId,BaseContext.getCurrentId());
        if(task.getDocumentId() == null) {
            knowledgePipelineExecutorService.runSpaceTask(task.getId());
        } else {
            knowledgePipelineExecutorService.runDocumentTask(task.getId());
        }
        return Result.success(task);
    }

    @PostMapping("/pipeline/retry-failed")
    public Result<List<SpaceKnowledgePipelineTaskVO>> retryFailed(@PathVariable Long spaceId) {
        List<SpaceKnowledgePipelineTaskVO> tasks = knowledgePipelineService.retryFailedTasks(spaceId,BaseContext.getCurrentId());
        for(SpaceKnowledgePipelineTaskVO task : tasks) {
            if(task.getDocumentId() == null) {
                knowledgePipelineExecutorService.runSpaceTask(task.getId());
            } else {
                knowledgePipelineExecutorService.runDocumentTask(task.getId());
            }
        }
        return Result.success(tasks);
    }

    @GetMapping("/pipeline/tasks")
    public Result<List<SpaceKnowledgePipelineTaskVO>> listTasks(@PathVariable Long spaceId) {
        return Result.success(knowledgePipelineService.listTasks(spaceId,BaseContext.getCurrentId()));
    }

    @GetMapping("/pipeline/tasks/{taskId}/events")
    public Result<List<SpaceKnowledgePipelineEventVO>> listTaskEvents(@PathVariable Long spaceId,
                                                                      @PathVariable Long taskId) {
        knowledgePipelineService.requireTask(spaceId,taskId,BaseContext.getCurrentId());
        return Result.success(pipelineEventService.listByTaskId(taskId).stream().map(this::toEventVO).toList());
    }

    @GetMapping("/dashboard")
    public Result<SpaceKnowledgeDashboardVO> dashboard(@PathVariable Long spaceId) {
        return Result.success(knowledgePipelineService.dashboard(spaceId,BaseContext.getCurrentId()));
    }

    @GetMapping("/documents")
    public Result<List<SpaceKnowledgeDocumentItemVO>> listDocuments(@PathVariable Long spaceId,
                                                                    @RequestParam(required = false) String category,
                                                                    @RequestParam(required = false) String tag,
                                                                    @RequestParam(required = false) String profileStatus) {
        return Result.success(knowledgePipelineService.listDocuments(spaceId,category,tag,profileStatus,BaseContext.getCurrentId()));
    }

    @GetMapping("/facets/categories")
    public Result<List<SpaceKnowledgeFacetVO>> categories(@PathVariable Long spaceId) {
        return Result.success(knowledgePipelineService.categories(spaceId,BaseContext.getCurrentId()));
    }

    @GetMapping("/facets/tags")
    public Result<List<SpaceKnowledgeFacetVO>> tags(@PathVariable Long spaceId) {
        return Result.success(knowledgePipelineService.tags(spaceId,BaseContext.getCurrentId()));
    }

    @GetMapping("/documents/{documentId}/profile")
    public Result<SpaceKnowledgeDocumentProfileVO> getProfile(@PathVariable Long spaceId,
                                                              @PathVariable Long documentId) {
        return Result.success(knowledgePipelineService.getProfile(spaceId,documentId,BaseContext.getCurrentId()));
    }

    @PutMapping("/documents/{documentId}/profile")
    public Result<SpaceKnowledgeDocumentProfileVO> updateProfile(@PathVariable Long spaceId,
                                                                 @PathVariable Long documentId,
                                                                 @RequestBody SpaceKnowledgeProfileUpdateDTO payload) {
        return Result.success(knowledgePipelineService.updateProfile(spaceId,documentId,payload,BaseContext.getCurrentId()));
    }

    @PutMapping("/documents/{documentId}/classify")
    public Result<SpaceKnowledgeDocumentProfileVO> classify(@PathVariable Long spaceId,
                                                            @PathVariable Long documentId,
                                                            @RequestBody SpaceKnowledgeProfileUpdateDTO payload) {
        return Result.success(knowledgePipelineService.updateProfile(spaceId,documentId,payload,BaseContext.getCurrentId()));
    }

    @PostMapping("/documents/{documentId}/reviewed")
    public Result<SpaceKnowledgeDocumentProfileVO> markReviewed(@PathVariable Long spaceId,
                                                                @PathVariable Long documentId) {
        return Result.success(knowledgePipelineService.markReviewed(spaceId,documentId,BaseContext.getCurrentId()));
    }

    @GetMapping("/documents/{documentId}/versions")
    public Result<List<SpaceKnowledgeProfileVersionVO>> listVersions(@PathVariable Long spaceId,
                                                                     @PathVariable Long documentId) {
        return Result.success(knowledgePipelineService.listProfileVersions(spaceId,documentId,BaseContext.getCurrentId()));
    }

    @GetMapping("/documents/{documentId}/versions/{versionId}/diff")
    public Result<SpaceKnowledgeProfileDiffVO> diffVersion(@PathVariable Long spaceId,
                                                           @PathVariable Long documentId,
                                                           @PathVariable Long versionId,
                                                           @RequestParam(required = false) Long compareTo) {
        return Result.success(knowledgePipelineService.diffProfileVersion(spaceId,documentId,versionId,compareTo,BaseContext.getCurrentId()));
    }

    @PostMapping("/documents/{documentId}/versions/{versionId}/restore")
    public Result<SpaceKnowledgeDocumentProfileVO> restoreVersion(@PathVariable Long spaceId,
                                                                  @PathVariable Long documentId,
                                                                  @PathVariable Long versionId) {
        return Result.success(knowledgePipelineService.restoreProfileVersion(spaceId,documentId,versionId,BaseContext.getCurrentId()));
    }

    @PostMapping("/documents/{documentId}/regenerate")
    public Result<SpaceKnowledgePipelineTaskVO> regenerate(@PathVariable Long spaceId,
                                                           @PathVariable Long documentId) {
        SpaceKnowledgePipelineTaskVO task = knowledgePipelineService.submitDocument(spaceId,documentId,BaseContext.getCurrentId());
        knowledgePipelineExecutorService.runDocumentTask(task.getId());
        return Result.success(task);
    }

    @PostMapping("/documents/{documentId}/reclassify")
    public Result<SpaceKnowledgePipelineTaskVO> reclassify(@PathVariable Long spaceId,
                                                           @PathVariable Long documentId) {
        SpaceKnowledgePipelineTaskVO task = knowledgePipelineService.submitDocument(spaceId,documentId,BaseContext.getCurrentId());
        knowledgePipelineExecutorService.runDocumentTask(task.getId());
        return Result.success(task);
    }

    @GetMapping("/documents/profiles")
    public Result<List<SpaceKnowledgeDocumentProfileVO>> listProfiles(@PathVariable Long spaceId) {
        return Result.success(knowledgePipelineService.listProfiles(spaceId,BaseContext.getCurrentId()));
    }

    private SpaceKnowledgePipelineEventVO toEventVO(SpaceKnowledgePipelineEvent event) {
        SpaceKnowledgePipelineEventVO vo = new SpaceKnowledgePipelineEventVO();
        vo.setId(event.getId());
        vo.setTaskId(event.getTaskId());
        vo.setSpaceId(event.getSpaceId());
        vo.setDocumentId(event.getDocumentId());
        vo.setStage(event.getStage());
        vo.setEventType(event.getEventType());
        vo.setEventStatus(event.getEventStatus());
        vo.setMessage(event.getMessage());
        vo.setInputSummary(event.getInputSummary());
        vo.setOutputSummary(event.getOutputSummary());
        vo.setErrorCode(event.getErrorCode());
        vo.setErrorMessage(event.getErrorMessage());
        vo.setEventTime(event.getEventTime());
        vo.setDurationMs(event.getDurationMs());
        vo.setTraceId(event.getTraceId());
        vo.setAttemptNo(event.getAttemptNo());
        vo.setCreatedAt(event.getCreatedAt());
        return vo;
    }
}
