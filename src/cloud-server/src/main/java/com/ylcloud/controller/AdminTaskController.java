package com.ylcloud.controller;

import com.ylcloud.Result;
import com.ylcloud.context.BaseContext;
import com.ylcloud.service.AdminPermissionService;
import com.ylcloud.service.AdminTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/tasks")
@RequiredArgsConstructor
public class AdminTaskController {
    private final AdminPermissionService permissions;
    private final AdminTaskService tasks;

    @GetMapping
    public Result<AdminTaskService.Page> page(@RequestParam(defaultValue="false") boolean archived,
            @RequestParam(required=false) String status, @RequestParam(required=false) String type,
            @RequestParam(required=false) Long creator, @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int pageSize) {
        permissions.requireAdmin();
        return Result.success(tasks.page(archived, status, type, creator, page, pageSize));
    }

    @PostMapping("/{id}/archive")
    public Result<Boolean> archive(@PathVariable Long id) {
        permissions.requireAdmin();
        tasks.archive(id, BaseContext.getCurrentId());
        return Result.success(true);
    }
}
