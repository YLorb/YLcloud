package com.ylcloud.controller;

import com.ylcloud.DTO.WebhookSubscriptionCreateDTO;
import com.ylcloud.Result;
import com.ylcloud.VO.WebhookSubscriptionCreatedVO;
import com.ylcloud.VO.WebhookSubscriptionVO;
import com.ylcloud.context.BaseContext;
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
import java.util.Set;

@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
public class WebhookSubscriptionController {
    private final WebhookSubscriptionService service;

    @PostMapping
    public Result<WebhookSubscriptionCreatedVO> create(@RequestBody @Valid WebhookSubscriptionCreateDTO dto) {
        return Result.success(service.create(BaseContext.getCurrentId(),dto));
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
        return Result.success(service.rotateSecret(BaseContext.getCurrentId(),subscriptionId));
    }

    @DeleteMapping("/{subscriptionId}")
    public Result<Boolean> disable(@PathVariable Long subscriptionId) {
        service.disable(BaseContext.getCurrentId(),subscriptionId);
        return Result.success(true);
    }
}
