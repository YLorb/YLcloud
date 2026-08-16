package com.ylcloud.controller;

import com.ylcloud.DTO.AsyncDemoCreateDTO;
import com.ylcloud.Exception.ForbiddenException;
import com.ylcloud.Result;
import com.ylcloud.context.BaseContext;
import com.ylcloud.entity.UnifiedAsyncTask;
import com.ylcloud.entity.User;
import com.ylcloud.mapper.LoginMapper;
import com.ylcloud.async.task.UnifiedTaskCenterService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/async")
public class AdminAsyncSmokeController {
    private final LoginMapper loginMapper;
    private final UnifiedTaskCenterService taskCenterService;

    public AdminAsyncSmokeController(LoginMapper loginMapper, UnifiedTaskCenterService taskCenterService) {
        this.loginMapper = loginMapper;
        this.taskCenterService = taskCenterService;
    }

    @PostMapping("/smoke")
    public Result<UnifiedAsyncTask> smoke(@RequestParam String release) {
        Long userId = BaseContext.getCurrentId();
        User user = loginMapper.getById(userId);
        if(user == null || !Boolean.TRUE.equals(user.getDeploymentOwner())) {
            throw new ForbiddenException("只有部署所有者可以执行发布冒烟任务");
        }
        AsyncDemoCreateDTO dto = new AsyncDemoCreateDTO();
        dto.setText("deployment-smoke");
        dto.setMode("SUCCESS");
        dto.setDelayMs(0);
        dto.setIdempotencyKey("deploy-" + release);
        return Result.success(taskCenterService.createDemo(dto,userId));
    }
}
