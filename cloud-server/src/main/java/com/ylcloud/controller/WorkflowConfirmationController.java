package com.ylcloud.controller;

import com.ylcloud.DTO.WorkflowConfirmationCreateDTO;
import com.ylcloud.Result;
import com.ylcloud.context.BaseContext;
import com.ylcloud.workflow.contract.WorkflowContracts.ConfirmationGrant;
import com.ylcloud.workflow.tool.WorkflowConfirmationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** 用户可像 Codex 一样选择“允许一次”或“允许类似操作”，并随时撤销类似授权。 */
@RestController
@RequestMapping("/api/workflow/confirmations")
public class WorkflowConfirmationController {
    private final WorkflowConfirmationService service;
    public WorkflowConfirmationController(WorkflowConfirmationService service) { this.service = service; }

    @PostMapping
    public Result<ConfirmationGrant> issue(@RequestBody @Valid WorkflowConfirmationCreateDTO dto) {
        return Result.success(service.issue(BaseContext.getCurrentId(), dto));
    }

    @DeleteMapping("/{grantId}")
    public Result<Boolean> revoke(@PathVariable UUID grantId) {
        service.revoke(BaseContext.getCurrentId(), grantId);
        return Result.success(true);
    }
}
