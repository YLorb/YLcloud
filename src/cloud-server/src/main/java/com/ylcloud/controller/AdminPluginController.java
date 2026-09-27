package com.ylcloud.controller;

import com.ylcloud.Result;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.plugin.manifest.PluginManifest;
import com.ylcloud.pluginapi.PluginApiService;
import com.ylcloud.service.AdminPermissionService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 部署级操作：沿用现有 Session、CSRF 和部署所有者权限，无匿名或 API Key 豁免。 */
@RestController
@RequestMapping("/api/admin/plugins")
public class AdminPluginController {
    private final AdminPermissionService permissions;
    private final PluginApiService plugins;
    public AdminPluginController(AdminPermissionService permissions, PluginApiService plugins) {
        this.permissions = permissions; this.plugins = plugins;
    }
    @PostMapping(consumes = "application/json")
    public ResponseEntity<Result<PluginApiService.View>> install(HttpServletRequest request) {
        permissions.requireDeploymentOwner();
        try { return response(201, plugins.install(request.getInputStream())); }
        catch (IOException ex) { throw new BaseException(400, "无法读取插件声明"); }
    }
    @GetMapping public Result<List<PluginApiService.View>> list() {
        permissions.requireDeploymentOwner(); return Result.success(plugins.list());
    }
    @GetMapping("/{id}") public Result<PluginApiService.View> get(@PathVariable String id) {
        permissions.requireDeploymentOwner(); return Result.success(plugins.get(id));
    }
    @PostMapping("/{id}/enable")
    public Result<PluginApiService.View> enable(@PathVariable String id, @RequestParam PluginManifest.RuntimeMode mode) {
        permissions.requireDeploymentOwner(); return Result.success(plugins.enable(id, mode));
    }
    @PostMapping("/{id}/disable")
    public ResponseEntity<Result<PluginApiService.DisableResult>> disable(@PathVariable String id,
            @RequestParam(defaultValue = "0") long waitMillis) {
        permissions.requireDeploymentOwner();
        var result = plugins.disable(id, waitMillis);
        return response(result.stopped() ? 200 : 202, result);
    }
    @DeleteMapping("/{id}") public Result<Void> uninstall(@PathVariable String id) {
        permissions.requireDeploymentOwner(); plugins.uninstall(id); return Result.success();
    }
    @PostMapping("/{id}/health-check") public Result<PluginApiService.View> checkHealth(@PathVariable String id) {
        permissions.requireDeploymentOwner(); return Result.success(plugins.checkHealth(id));
    }
    private static <T> ResponseEntity<Result<T>> response(int status, T data) {
        var result = Result.success(data); result.setCode(status);
        return ResponseEntity.status(status).body(result);
    }
}
