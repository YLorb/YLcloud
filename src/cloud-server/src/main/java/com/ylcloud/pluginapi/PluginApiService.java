package com.ylcloud.pluginapi;

import com.ylcloud.Exception.BaseException;
import com.ylcloud.plugin.manager.*;
import com.ylcloud.plugin.manifest.*;
import com.ylcloud.plugin.registry.ProviderRegistryException;
import com.ylcloud.plugin.runtime.*;
import org.slf4j.LoggerFactory;
import java.io.InputStream;
import java.time.Duration;
import java.util.*;

/** HTTP 用例层：只登记 Manifest，进程重启后记录丢失，不下载、解压或部署插件包。 */
public final class PluginApiService {
    public record View(String id, String name, String version, List<String> providerIds,
                       Set<PluginManifest.RuntimeMode> declaredModes, Set<PluginManifest.RuntimeMode> configuredModes,
                       PluginManager.State managementState, PluginManifest.RuntimeMode selectedMode, long revision,
                       PluginRuntime.State runtimeState, PluginRuntime.HealthSnapshot health,
                       String installationKind, String persistence) { }
    public record DisableResult(View plugin, boolean stopped) { }
    private record Key(String id, String version, PluginManifest.RuntimeMode mode) { }
    private final PluginManager manager;
    private final Map<Key, PluginBackendBinding> bindings;
    private final PluginManifestReader reader = new PluginManifestReader();

    public PluginApiService(PluginManager manager, List<PluginBackendBinding> bindings) {
        this.manager = Objects.requireNonNull(manager);
        Map<Key, PluginBackendBinding> map = new HashMap<>();
        for (var binding : bindings) {
            var key = new Key(binding.pluginId(), binding.version(), binding.mode());
            if (map.putIfAbsent(key, binding) != null) { throw new IllegalArgumentException("duplicate plugin backend binding"); }
        }
        this.bindings = Map.copyOf(map);
    }

    /** 使用严格、有 64 KiB 上限的 Reader；不能先经普通 JSON 反序列化而丢失重复字段。 */
    public View install(InputStream source) {
        return execute(() -> { var installed = manager.install(reader.read(source)); return view(installed.manifest().id()); });
    }
    public List<View> list() {
        return execute(() -> {
            synchronized (manager) { return manager.list().stream().map(s -> view(s.manifest().id())).toList(); }
        });
    }
    public View get(String id) { return execute(() -> view(id)); }

    public View enable(String id, PluginManifest.RuntimeMode mode) {
        return execute(() -> {
            var manifest = manager.get(id).manifest();
            if (!manifest.runtimeModes().contains(mode)) { throw new BaseException(400, "插件未声明该部署方式"); }
            var binding = bindings.get(new Key(id, manifest.version(), mode));
            if (binding == null) { throw new BaseException(503, "该插件版本及部署方式尚未配置运行后端与健康样例"); }
            manager.enableIfInstalled(id, mode, binding.factory(), binding.limits(), binding.healthPlan(), manifest);
            return view(id);
        });
    }

    /** 重复禁用用于继续清理；返回 stopped=false 时调用方稍后重试，不能当成资源已释放。 */
    public DisableResult disable(String id, long waitMillis) {
        return execute(() -> {
            if (waitMillis < 0 || waitMillis > 5000) { throw new BaseException(400, "等待时间必须为 0 至 5000 毫秒"); }
            PluginManager.Snapshot disabled;
            PluginRuntime.HealthSnapshot runtime;
            synchronized (manager) {
                disabled = manager.disable(id);
                runtime = manager.runtimeHealth(id);
            }
            boolean stopped = manager.awaitStoppedIfInstalled(id, Duration.ofMillis(waitMillis), disabled.manifest(),
                    runtime == null ? null : runtime.runtimeId());
            synchronized (manager) {
                var current = manager.get(id);
                var health = manager.runtimeHealth(id);
                if (current.manifest() != disabled.manifest() || current.state() != PluginManager.State.DISABLED
                        || !Objects.equals(runtime == null ? null : runtime.runtimeId(), health == null ? null : health.runtimeId())) {
                    throw new BaseException(409, "等待停止期间插件发生变化，请重新查询状态");
                }
                return new DisableResult(view(id), stopped);
            }
        });
    }
    public void uninstall(String id) { execute(() -> { manager.uninstall(id); return null; }); }
    public View checkHealth(String id) {
        return execute(() -> {
            var snapshot = manager.get(id);
            var binding = bindings.get(new Key(id, snapshot.manifest().version(), snapshot.mode()));
            if (binding == null) { throw new BaseException(409, "插件必须先通过已配置的运行后端启用"); }
            manager.checkHealth(id, binding.healthPlan());
            return view(id);
        });
    }

    private View view(String id) {
        synchronized (manager) {
            var snapshot = manager.get(id);
            var manifest = snapshot.manifest();
            Set<PluginManifest.RuntimeMode> configured = new HashSet<>();
            for (var mode : manifest.runtimeModes()) {
                if (bindings.containsKey(new Key(id, manifest.version(), mode))) { configured.add(mode); }
            }
            return new View(id, manifest.name(), manifest.version(), manifest.providers().stream().map(ProviderManifest::id).toList(),
                    manifest.runtimeModes(), Set.copyOf(configured), snapshot.state(), snapshot.mode(), snapshot.revision(),
                    manager.runtimeState(id), manager.runtimeHealth(id), "MANIFEST_REGISTRATION", "PROCESS_MEMORY");
        }
    }

    /** 只转出固定安全消息；禁止把插件异常原因交给通用 handler 无筛选地记录。 */
    private <T> T execute(Action<T> action) {
        try { return action.run(); }
        catch (BaseException ex) { throw ex; }
        catch (PluginManifestException ex) {
            throw new BaseException(ex.code() == PluginManifestException.Code.TOO_LARGE ? 413 : 400,
                    "插件声明无效：" + ex.code().name());
        } catch (PluginManagerException ex) {
            int status = ex.code() == PluginManagerException.Code.NOT_INSTALLED ? 404 : 409;
            throw new BaseException(status, "插件管理操作被拒绝：" + ex.code().name());
        } catch (ProviderRegistryException ex) {
            throw new BaseException(409, "插件能力注册失败：" + ex.code().name());
        } catch (PluginHealthException ex) {
            throw new BaseException(422, "插件健康验证未通过，未发布或已暂停新调用，请查询状态");
        } catch (PluginRuntimeException ex) {
            throw new BaseException(503, "插件运行操作失败：" + ex.code().name() + "，请查询状态并重试清理");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BaseException(503, "插件操作等待被中断，请查询实际状态");
        } catch (IllegalArgumentException ex) {
            throw new BaseException(400, "插件操作参数或后端健康配置不匹配");
        } catch (Exception ex) {
            throw new BaseException(500, "插件管理操作失败，请查询实际状态");
        }
    }
    @FunctionalInterface private interface Action<T> { T run() throws Exception; }

    /** 容器关闭时尽力清理；不把残留任务报告成已经停止。后端 close 必须自行限制 I/O。 */
    public void shutdown() {
        for (var snapshot : manager.list()) {
            try {
                manager.disable(snapshot.manifest().id());
                if (!manager.awaitStopped(snapshot.manifest().id(), Duration.ofSeconds(2))) {
                    LoggerFactory.getLogger(getClass()).warn("Plugin runtime still stopping during application shutdown");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException ex) {
                LoggerFactory.getLogger(getClass()).warn("Plugin runtime cleanup failed during application shutdown");
            }
        }
    }
}
