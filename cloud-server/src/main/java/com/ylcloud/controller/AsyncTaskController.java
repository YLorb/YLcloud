package com.ylcloud.controller;

import com.ylcloud.Result;
import com.ylcloud.VO.AsyncTaskVO;
import com.ylcloud.VO.AsyncTaskDetailVO;
import com.ylcloud.context.BaseContext;
import com.ylcloud.service.AsyncTaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 当前用户的真实后台任务入口。
 */
@RestController
@RequestMapping("/api/async")
public class AsyncTaskController {
    private final AsyncTaskService asyncTaskService;

    public AsyncTaskController(AsyncTaskService asyncTaskService) {
        this.asyncTaskService = asyncTaskService;
    }

    @GetMapping
    public Result<List<AsyncTaskVO>> list(@RequestParam(required = false) Long spaceId) {
        return Result.success(asyncTaskService.listUserTasks(BaseContext.getCurrentId(),spaceId));
    }

    @GetMapping("/{source}/{taskId}")
    public Result<AsyncTaskDetailVO> detail(@PathVariable String source, @PathVariable Long taskId) {
        return Result.success(asyncTaskService.getUserTask(BaseContext.getCurrentId(),source,taskId));
    }
}
