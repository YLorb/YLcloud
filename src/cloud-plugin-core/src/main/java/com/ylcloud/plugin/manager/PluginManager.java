package com.ylcloud.plugin.manager;

import com.ylcloud.plugin.manifest.PluginManifest;
import com.ylcloud.plugin.manifest.ProviderManifest;
import com.ylcloud.plugin.registry.ProviderRegistry;
import com.ylcloud.plugin.spi.PluginProvider;
import com.ylcloud.plugin.runtime.PluginRuntime;
import com.ylcloud.plugin.runtime.PluginRuntimeFactory;
import com.ylcloud.plugin.runtime.PluginHealthPlan;
import com.ylcloud.plugin.spi.document.DocumentParserProvider;

import java.time.Duration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.ylcloud.plugin.manager.PluginManagerException.Code.*;

/**
 * 进程内插件管理。install 只登记已校验的说明书，不下载/解压文件。
 * enable 的工厂重载负责 Runtime 生命周期；Map 重载保留为宿主管理资源的低层入口。
 * 工厂适配实际执行后端；带 HealthPlan 的启用在整包验证通过后才发布。
 * Manager 应独占所管理 ID 的 Registry 写权限；查询和业务调用可直接使用 Registry。
 */
public final class PluginManager {
    private final ProviderRegistry registry;
    private final Map<String, Entry> plugins = new HashMap<>();
    private final Map<String, String> providerOwners = new HashMap<>();

    public PluginManager(ProviderRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** 包登记后处于 DISABLED；即使禁用也保留 Provider ID 的归属，卸载才释放。 */
    public synchronized Snapshot install(PluginManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        if (plugins.containsKey(manifest.id())) {
            throw failure(ALREADY_INSTALLED, "plugin is already installed");
        }
        for (ProviderManifest provider : manifest.providers()) {
            if (providerOwners.containsKey(provider.id())) {
                throw failure(PROVIDER_ID_CONFLICT, "provider id belongs to another installed plugin");
            }
        }
        Entry entry = new Entry(manifest);
        plugins.put(manifest.id(), entry);
        for (ProviderManifest provider : manifest.providers()) {
            providerOwners.put(provider.id(), manifest.id());
        }
        return snapshot(entry);
    }

    /**
     * 低层兼容入口：资源由调用方负责，不提供 Runtime 门禁/排空。业务接入使用工厂重载。
     * 两步启用：锁外采集插件元数据，锁内复核版本并整批发布。
     * 回调期间发生禁用/卸载/其他启用，旧操作必须失败，不能让已取消的启用重新生效。
     * 语言、表格、公式支持目前只有 Manifest 声明，SPI 无查询接口，不能声称已验证。
     */
    public Snapshot enable(String pluginId, PluginManifest.RuntimeMode mode,
                           Map<String, ? extends PluginProvider> instances) {
        Objects.requireNonNull(mode, "mode");
        Map<String, ? extends PluginProvider> supplied = Map.copyOf(instances);
        Entry expected;
        long revision;
        synchronized (this) {
            expected = requireEntry(pluginId);
            if (expected.state != State.DISABLED || hasLiveRuntime(expected)) {
                throw failure(INVALID_STATE, "plugin must be disabled before enabling");
            }
            if (!expected.manifest.runtimeModes().contains(mode)) {
                throw failure(UNSUPPORTED_RUNTIME, "runtime mode is not declared");
            }
            expected.runtime = null; // Map 入口由调用方管理资源，不沿用上一轮已停止的 Runtime。
            revision = expected.revision;
        }
        return publish(pluginId, mode, supplied, expected, revision);
    }

    /**
     * 受管启用：启动前先关联 Runtime 并递增修订号；并发禁用使该次发布失效。
     * 失败时尝试清理，未退出/关闭失败的 Runtime 仍保留在 Entry 中，可 awaitStopped 重试。
     */
    /** Phase 5 低层入口：未验证健康；生产启用使用带 HealthPlan 的 enable。 */
    public Snapshot enableUnverified(String pluginId, PluginManifest.RuntimeMode mode,
                                    PluginRuntimeFactory factory, PluginRuntime.Limits limits) {
        return enableManaged(pluginId, mode, factory, limits, null, null);
    }

    /** 整包样例通过后才发布；缺少样例覆盖时在启动前拒绝。 */
    public Snapshot enable(String pluginId, PluginManifest.RuntimeMode mode,
                           PluginRuntimeFactory factory, PluginRuntime.Limits limits, PluginHealthPlan plan) {
        Objects.requireNonNull(plan, "healthPlan");
        return enableManaged(pluginId, mode, factory, limits, plan, null);
    }

    /** API 选择后端后，防止期间同 ID 卸载重装导致旧后端用于新安装。比较声明对象身份。 */
    public Snapshot enableIfInstalled(String pluginId, PluginManifest.RuntimeMode mode,
                                      PluginRuntimeFactory factory, PluginRuntime.Limits limits,
                                      PluginHealthPlan plan, PluginManifest expectedManifest) {
        Objects.requireNonNull(plan, "healthPlan");
        Objects.requireNonNull(expectedManifest, "expectedManifest");
        return enableManaged(pluginId, mode, factory, limits, plan, expectedManifest);
    }

    private Snapshot enableManaged(String pluginId, PluginManifest.RuntimeMode mode,
                                   PluginRuntimeFactory factory, PluginRuntime.Limits limits, PluginHealthPlan plan,
                                   PluginManifest expectedManifest) {
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(mode, "mode");
        Entry expected;
        long revision;
        PluginRuntime runtime;
        synchronized (this) {
            expected = requireEntry(pluginId);
            if (expectedManifest != null && expected.manifest != expectedManifest) {
                throw failure(CONCURRENT_CHANGE, "plugin installation changed during backend selection");
            }
            if (expected.state != State.DISABLED || hasLiveRuntime(expected)) {
                throw failure(INVALID_STATE, "previous runtime must stop before enabling");
            }
            if (!expected.manifest.runtimeModes().contains(mode)) {
                throw failure(UNSUPPORTED_RUNTIME, "runtime mode is not declared");
            }
            if (plan != null) { plan.validate(expected.manifest); }
            runtime = new PluginRuntime(expected.manifest, mode, limits);
            expected.runtime = runtime;
            revision = ++expected.revision;
        }
        try {
            runtime.start(factory);
            if (plan != null) { runtime.checkHealth(plan); }
            return publish(pluginId, mode, runtime.providers(), expected, revision);
        } catch (RuntimeException | Error failure) {
            runtime.stopAccepting();
            try { runtime.awaitStopped(Duration.ZERO); }
            catch (InterruptedException cleanupFailure) {
                Thread.currentThread().interrupt();
                failure.addSuppressed(cleanupFailure);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /** 先禁用再等待；等待不持 Manager 锁。false 表示任务/启动仍未退出，可稍后重试。 */
    public boolean awaitStopped(String pluginId, Duration budget) throws InterruptedException {
        return awaitStoppedInternal(pluginId, budget, null, null);
    }

    /** API 只等待指定安装的指定运行，不能误停期间新启动的 Runtime。 */
    public boolean awaitStoppedIfInstalled(String pluginId, Duration budget, PluginManifest expectedManifest,
                                           java.util.UUID runtimeId) throws InterruptedException {
        Objects.requireNonNull(expectedManifest);
        return awaitStoppedInternal(pluginId, budget, expectedManifest, runtimeId);
    }

    private boolean awaitStoppedInternal(String pluginId, Duration budget, PluginManifest expectedManifest,
                                         java.util.UUID runtimeId) throws InterruptedException {
        PluginRuntime runtime;
        synchronized (this) {
            Entry entry = requireEntry(pluginId);
            if (entry.state != State.DISABLED) {
                throw failure(INVALID_STATE, "disable plugin before waiting for stop");
            }
            runtime = entry.runtime;
            if (expectedManifest != null && (entry.manifest != expectedManifest
                    || !Objects.equals(runtimeId, runtime == null ? null : runtime.health().runtimeId()))) {
                throw failure(CONCURRENT_CHANGE, "plugin changed before waiting for stop");
            }
            // STARTING 时管理状态仍是 DISABLED；直接等待停止也必须使待发布操作失效。
            if (hasLiveRuntime(entry)) {
                Objects.requireNonNull(budget, "budget");
                if (budget.isNegative() || budget.compareTo(Duration.ofDays(1)) > 0) {
                    throw new IllegalArgumentException("drain budget must be between zero and one day");
                }
                entry.revision++;
                runtime.stopAccepting();
            }
        }
        return runtime == null || runtime.awaitStopped(budget);
    }

    /** null 表示未使用受管 Runtime；运行状态和 Manager 的发布状态分别观察。 */
    public synchronized PluginRuntime.State runtimeState(String pluginId) {
        var runtime = requireEntry(pluginId).runtime;
        return runtime == null ? null : runtime.state();
    }

    public synchronized PluginRuntime.HealthSnapshot runtimeHealth(String pluginId) {
        var runtime = requireEntry(pluginId).runtime;
        return runtime == null ? null : runtime.health();
    }

    /** 健康复查在锁外执行；返回前确认仍是同一安装和同一轮运行。无后台轮询。 */
    public PluginRuntime.HealthSnapshot checkHealth(String pluginId, PluginHealthPlan plan) {
        Entry expected;
        PluginRuntime runtime;
        long revision;
        synchronized (this) {
            expected = requireEntry(pluginId);
            runtime = expected.runtime;
            revision = expected.revision;
            if (expected.state != State.ENABLED || runtime == null) {
                throw failure(INVALID_STATE, "plugin must have an enabled managed runtime");
            }
        }
        var result = runtime.checkHealth(plan);
        synchronized (this) {
            if (plugins.get(pluginId) != expected || expected.runtime != runtime || expected.revision != revision) {
                throw failure(CONCURRENT_CHANGE, "plugin changed during health check");
            }
            return result;
        }
    }

    private static boolean hasLiveRuntime(Entry entry) {
        return entry.runtime != null && entry.runtime.state() != PluginRuntime.State.STOPPED;
    }

    private Snapshot publish(String pluginId, PluginManifest.RuntimeMode mode,
                             Map<String, ? extends PluginProvider> supplied, Entry expected, long revision) {
        var declaredIds = expected.manifest.providers().stream()
                .map(ProviderManifest::id).collect(java.util.stream.Collectors.toSet());
        if (!supplied.keySet().equals(declaredIds)) {
            throw failure(MANIFEST_MISMATCH, "provider set does not match manifest");
        }
        List<ProviderRegistry.PreparedRegistration> prepared = new ArrayList<>();
        for (ProviderManifest declaration : expected.manifest.providers()) {
            PluginProvider instance = supplied.get(declaration.id());
            // V1 仅支持文档解析；新增能力时显式扩展该分派，不接受任意实现类名。
            if (declaration.capability() != ProviderManifest.Capability.DOCUMENT_PARSER
                    || !(instance instanceof DocumentParserProvider parser)) {
                throw failure(MANIFEST_MISMATCH, "provider capability does not match manifest");
            }
            var candidate = registry.prepare(DocumentParserProvider.class, parser);
            if (!candidate.descriptor().id().equals(declaration.id())
                    || !candidate.descriptor().version().equals(declaration.version())
                    || !candidate.documentMediaTypes().equals(declaration.documentParsing().mediaTypes())) {
                throw failure(MANIFEST_MISMATCH, "provider metadata does not match manifest");
            }
            prepared.add(candidate);
        }
        synchronized (this) {
            if (plugins.get(pluginId) != expected || expected.revision != revision) {
                throw failure(CONCURRENT_CHANGE, "plugin changed during enable preparation");
            }
            // Registry 在全部冲突检查通过后才发布；失败时 entry 保持禁用，无需逐个回滚。
            expected.registrations = registry.registerAll(prepared);
            expected.mode = mode;
            expected.state = State.ENABLED;
            expected.revision++;
            return snapshot(expected);
        }
    }

    /**
     * 受管实例先关闭入口，旧引用也不能提交新任务；随后撤销 Registry 登记。
     * Map 重载的裸实例仍由宿主负责生命周期，已有引用不受此门禁保护。
     * 已禁用时也递增修订号，以取消正在锁外准备的旧启用操作。
     */
    public synchronized Snapshot disable(String pluginId) {
        Entry entry = requireEntry(pluginId);
        if (entry.runtime != null) { entry.runtime.stopAccepting(); }
        registry.unregisterAll(entry.registrations);
        entry.registrations = List.of();
        entry.state = State.DISABLED;
        entry.mode = null;
        entry.revision++;
        return snapshot(entry);
    }

    /** 必须先禁用；这里只释放登记信息，不删除插件文件或停止进程。 */
    public synchronized void uninstall(String pluginId) {
        Entry entry = requireEntry(pluginId);
        if (entry.state != State.DISABLED || hasLiveRuntime(entry)) {
            throw failure(INVALID_STATE, "disable and stop plugin before uninstalling");
        }
        plugins.remove(pluginId);
        entry.manifest.providers().forEach(p -> providerOwners.remove(p.id()));
    }

    public synchronized Snapshot get(String pluginId) { return snapshot(requireEntry(pluginId)); }

    public synchronized List<Snapshot> list() {
        return plugins.values().stream().map(PluginManager::snapshot)
                .sorted(Comparator.comparing(s -> s.manifest().id())).toList();
    }

    private Entry requireEntry(String id) {
        Objects.requireNonNull(id, "pluginId");
        Entry entry = plugins.get(id);
        if (entry == null) { throw failure(NOT_INSTALLED, "plugin is not installed"); }
        return entry;
    }

    private static Snapshot snapshot(Entry entry) {
        return new Snapshot(entry.manifest, entry.state, entry.mode, entry.revision);
    }

    private static PluginManagerException failure(PluginManagerException.Code code, String message) {
        return new PluginManagerException(code, message);
    }

    public enum State { DISABLED, ENABLED }

    /** 不可变观察结果；mode 仅 ENABLED 时有值。revision 是管理操作修订号，不是健康状态。 */
    public record Snapshot(PluginManifest manifest, State state,
                           PluginManifest.RuntimeMode mode, long revision) { }

    private static final class Entry {
        private final PluginManifest manifest;
        private State state = State.DISABLED;
        private PluginManifest.RuntimeMode mode;
        private long revision;
        private PluginRuntime runtime;
        private List<ProviderRegistry.Registration> registrations = List.of();
        private Entry(PluginManifest manifest) { this.manifest = manifest; }
    }
}
