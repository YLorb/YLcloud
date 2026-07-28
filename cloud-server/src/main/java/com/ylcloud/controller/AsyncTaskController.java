package com.ylcloud.controller;

import com.ylcloud.Result;
import com.ylcloud.VO.AsyncTaskVO;
import com.ylcloud.VO.AsyncTaskDetailVO;
import com.ylcloud.VO.AsyncTaskPageVO;
import com.ylcloud.DTO.AsyncDemoCreateDTO;
import com.ylcloud.DTO.AsyncTaskOperationDTO;
import com.ylcloud.async.task.UnifiedTaskCenterService;
import com.ylcloud.entity.UnifiedAsyncTask;
import com.ylcloud.Exception.ForbiddenException;
import com.ylcloud.service.SecurityAuditService;
import jakarta.validation.Valid;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import com.ylcloud.context.BaseContext;
import com.ylcloud.service.AsyncTaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 当前用户的真实后台任务入口。
 */
@RestController
@RequestMapping("/api/async")
public class AsyncTaskController {
    private final AsyncTaskService asyncTaskService;
    private final UnifiedTaskCenterService taskCenterService;
    private final Environment environment;
    private final SecurityAuditService auditService;

    public AsyncTaskController(AsyncTaskService asyncTaskService,
                               UnifiedTaskCenterService taskCenterService,
                               Environment environment,
                               SecurityAuditService auditService) {
        this.asyncTaskService = asyncTaskService;
        this.taskCenterService = taskCenterService;
        this.environment = environment;
        this.auditService = auditService;
    }

    @GetMapping
    public Result<List<AsyncTaskVO>> list(@RequestParam(required = false) Long spaceId) {
        return Result.success(asyncTaskService.listUserTasks(BaseContext.getCurrentId(),spaceId));
    }

    @GetMapping("/{source}/{taskId}")
    public Result<AsyncTaskDetailVO> detail(@PathVariable String source, @PathVariable Long taskId) {
        return Result.success(asyncTaskService.getUserTask(BaseContext.getCurrentId(),source,taskId));
    }

    @GetMapping("/page")
    public Result<AsyncTaskPageVO> page(@RequestParam(required = false) Long spaceId,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(required = false) String domain,
                                        @RequestParam(required = false) String type,
                                        @RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(asyncTaskService.pageUserTasks(
                BaseContext.getCurrentId(),spaceId,status,domain,type,page,pageSize
        ));
    }

    @PostMapping("/demo")
    public Result<UnifiedAsyncTask> createDemo(@RequestBody @Valid AsyncDemoCreateDTO dto) {
        boolean allowed = java.util.Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> "dev".equals(profile) || "test".equals(profile));
        if(!allowed) throw new ForbiddenException("示范任务入口只在开发或测试环境开放");
        return Result.success(taskCenterService.createDemo(dto,BaseContext.getCurrentId()));
    }

    @PostMapping("/{taskId}/retry")
    public Result<Boolean> retry(@PathVariable Long taskId,
                                 @RequestBody(required = false) @Valid AsyncTaskOperationDTO dto) {
        Long userId = BaseContext.getCurrentId();
        try {
            taskCenterService.retry(taskId, userId, dto == null ? null : dto.getReason());
            auditService.recordSuccess("ASYNC_TASK", "RETRY", userId, null,
                    "TASK", taskId.toString(), null, Map.of());
            return Result.success(true);
        } catch (Exception e) {
            auditService.recordFailure("ASYNC_TASK", "RETRY", userId, null,
                    "TASK", taskId.toString(), null, e.getMessage(), Map.of());
            throw e;
        }
    }

    @PostMapping("/{taskId}/cancel")
    public Result<Boolean> cancel(@PathVariable Long taskId,
                                  @RequestBody(required = false) @Valid AsyncTaskOperationDTO dto) {
        Long userId = BaseContext.getCurrentId();
        try {
            taskCenterService.cancel(taskId, userId, dto == null ? null : dto.getReason());
            auditService.recordSuccess("ASYNC_TASK", "CANCEL", userId, null,
                    "TASK", taskId.toString(), null, Map.of());
            return Result.success(true);
        } catch (Exception e) {
            auditService.recordFailure("ASYNC_TASK", "CANCEL", userId, null,
                    "TASK", taskId.toString(), null, e.getMessage(), Map.of());
            throw e;
        }
    }
}
