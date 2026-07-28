package com.ylcloud.controller;

import com.ylcloud.DTO.WebhookSubscriptionCreateDTO;
import com.ylcloud.Result;
import com.ylcloud.VO.WebhookSubscriptionCreatedVO;
import com.ylcloud.VO.WebhookSubscriptionVO;
import com.ylcloud.context.BaseContext;
import com.ylcloud.service.SecurityAuditService;
import com.ylcloud.service.WebhookSubscriptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
public class WebhookSubscriptionController {
    private final WebhookSubscriptionService service;
    private final SecurityAuditService auditService;

    @PostMapping
    public Result<WebhookSubscriptionCreatedVO> create(@RequestBody @Valid WebhookSubscriptionCreateDTO dto) {
        Long userId = BaseContext.getCurrentId();
        try {
            WebhookSubscriptionCreatedVO result = service.create(userId, dto);
            auditService.recordSuccess("WEBHOOK", "CREATE", userId, null,
                    "WEBHOOK", result.subscription() != null && result.subscription().getId() != null ? result.subscription().getId().toString() : null, dto.getTargetUrl(),
                    Map.of("eventTypes", dto.getEventTypes() != null ? String.join(",", dto.getEventTypes()) : ""));
            return Result.success(result);
        } catch (Exception e) {
            auditService.recordFailure("WEBHOOK", "CREATE", userId, null,
                    "WEBHOOK", null, dto.getTargetUrl(), e.getMessage(), Map.of());
            throw e;
        }
    }

    @GetMapping
    public Result<List<WebhookSubscriptionVO>> list() {
        return Result.success(service.list(BaseContext.getCurrentId()));
    }

    @GetMapping("/event-types")
    public Result<Set<String>> eventTypes() {
        return Result.success(service.eventCatalog());
    }

    @PostMapping("/{subscriptionId}/rotate-secret")
    public Result<WebhookSubscriptionCreatedVO> rotate(@PathVariable Long subscriptionId) {
        Long userId = BaseContext.getCurrentId();
        try {
            WebhookSubscriptionCreatedVO result = service.rotateSecret(userId, subscriptionId);
            auditService.recordSuccess("WEBHOOK", "ROTATE_SECRET", userId, null,
                    "WEBHOOK", subscriptionId.toString(), null, Map.of());
            return Result.success(result);
        } catch (Exception e) {
            auditService.recordFailure("WEBHOOK", "ROTATE_SECRET", userId, null,
                    "WEBHOOK", subscriptionId.toString(), null, e.getMessage(), Map.of());
            throw e;
        }
    }

    @DeleteMapping("/{subscriptionId}")
    public Result<Boolean> disable(@PathVariable Long subscriptionId) {
        Long userId = BaseContext.getCurrentId();
        try {
            service.disable(userId, subscriptionId);
            auditService.recordSuccess("WEBHOOK", "DISABLE", userId, null,
                    "WEBHOOK", subscriptionId.toString(), null, Map.of());
            return Result.success(true);
        } catch (Exception e) {
            auditService.recordFailure("WEBHOOK", "DISABLE", userId, null,
                    "WEBHOOK", subscriptionId.toString(), null, e.getMessage(), Map.of());
            throw e;
        }
    }
}
