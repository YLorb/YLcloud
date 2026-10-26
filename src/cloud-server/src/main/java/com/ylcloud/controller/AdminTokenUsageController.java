package com.ylcloud.controller;

import com.ylcloud.Result;
import com.ylcloud.context.BaseContext;
import com.ylcloud.service.AdminPermissionService;
import com.ylcloud.service.metering.ApiTokenMeteringService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/quota/token-usage")
@RequiredArgsConstructor
public class AdminTokenUsageController {
    private final AdminPermissionService permissions;
    private final ApiTokenMeteringService metering;
    public record Resolution(@NotNull @Min(0) @Max(Integer.MAX_VALUE) Long promptTokens,
                             @NotNull @Min(0) @Max(Integer.MAX_VALUE) Long completionTokens,
                             @NotBlank @Size(max=500) String evidence) {}
    @GetMapping("/unresolved")
    public Result<List<Map<String,Object>>> unresolved(@RequestParam(defaultValue="50") int limit) {
        permissions.requireAdmin();
        return Result.success(metering.unresolvedRecords(limit));
    }
    @PutMapping("/{callId}/resolve")
    public Result<Boolean> resolve(@PathVariable String callId,@Valid @RequestBody Resolution resolution) {
        permissions.requireAdmin();
        metering.resolve(callId,resolution.promptTokens(),resolution.completionTokens(),resolution.evidence(),BaseContext.getCurrentId());
        return Result.success(true);
    }
}
