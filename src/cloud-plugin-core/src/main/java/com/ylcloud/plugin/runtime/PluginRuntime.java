package com.ylcloud.plugin.runtime;

import com.ylcloud.plugin.manifest.*;
import com.ylcloud.plugin.spi.*;
import com.ylcloud.plugin.spi.document.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次运行的资源所有者。每包独立、有界线程池；注册的是受控包装实例。
 * STOPPING 先拒绝新调用，再排空已接收任务；超时不等于线程已退出，不提前释放资源。
 */
public final class PluginRuntime {
    public record Limits(int workers, int queueCapacity, int maxSnapshotBytes) {
        /** 默认每份文档最多 16 MiB；宿主可显式调整，最大 64 MiB。 */
        public Limits(int workers, int queueCapacity) { this(workers, queueCapacity, 16 * 1024 * 1024); }
        public Limits {
            if (workers < 1 || workers > 32 || queueCapacity < 1 || queueCapacity > 1024
                    || maxSnapshotBytes < 1 || maxSnapshotBytes > 64 * 1024 * 1024) {
                throw new IllegalArgumentException("runtime limits out of range");
            }
        }
    }
    public enum State { STARTING, RUNNING, STOPPING, STOP_FAILED, STOPPED }
    public enum Health { UNCHECKED, CHECKING, HEALTHY, UNHEALTHY, ISOLATED, INACTIVE }
    public record HealthSnapshot(UUID runtimeId, Health status, int consecutiveFailures, Instant checkedAt) { }
    private final UUID runtimeId = UUID.randomUUID();
    private Health health = Health.UNCHECKED;
    private int consecutiveFailures;
    private Instant checkedAt;
    private boolean checking;
    private boolean isolated;
    private boolean healthBlocked;
    private static final int FAILURE_THRESHOLD = 3;
    private final Map<String, DocumentParserProvider> targets = new HashMap<>();
    private final Object gate = new Object();
    private final Object cleanup = new Object();
    private final PluginManifest manifest;
    private final PluginManifest.RuntimeMode mode;
    private final ThreadPoolExecutor executor;
    private final Semaphore admissions;
    private final int maxSnapshotBytes;
    private final CountDownLatch startFinished = new CountDownLatch(1);
    private State state = State.STARTING;
    private boolean startClaimed;
    private PluginRuntimeFactory.Session session;
    private Map<String, PluginProvider> providers = Map.of();

    public PluginRuntime(PluginManifest manifest, PluginManifest.RuntimeMode mode, Limits limits) {
        this.manifest = Objects.requireNonNull(manifest);
        this.mode = Objects.requireNonNull(mode);
        Objects.requireNonNull(limits);
        if (!manifest.runtimeModes().contains(mode)) {
            throw new IllegalArgumentException("runtime mode is not declared");
        }
        admissions = new Semaphore(limits.workers() + limits.queueCapacity());
        maxSnapshotBytes = limits.maxSnapshotBytes();
        executor = new ThreadPoolExecutor(limits.workers(), limits.workers(), 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(limits.queueCapacity()), task -> {
                    Thread thread = new Thread(task, "plugin-" + manifest.id());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /** 每个 Runtime 只能启动一次。Manager 在调用前先保存 Runtime，保证失败资源可追踪。 */
    public void start(PluginRuntimeFactory factory) {
        Objects.requireNonNull(factory);
        synchronized (gate) {
            if (startClaimed) { throw new IllegalStateException("runtime already started"); }
            startClaimed = true;
        }
        try {
            session = Objects.requireNonNull(factory.start(manifest, mode), "session");
            var originals = Map.copyOf(session.providers());
            Set<String> ids = new HashSet<>();
            manifest.providers().forEach(p -> ids.add(p.id()));
            if (!originals.keySet().equals(ids)) { throw new IllegalArgumentException("provider set mismatch"); }
            Map<String, PluginProvider> wrapped = new HashMap<>();
            for (var declaration : manifest.providers()) {
                if (!(originals.get(declaration.id()) instanceof DocumentParserProvider parser)) {
                    throw new IllegalArgumentException("unsupported provider capability");
                }
                targets.put(declaration.id(), parser);
                // 捕获真实元数据，不能拿 Manifest 冒充实例声明；Manager 仍会核对一致性。
                var descriptor = Objects.requireNonNull(parser.descriptor());
                var mediaTypes = Set.copyOf(parser.supportedMediaTypes());
                wrapped.put(declaration.id(), new DocumentParserProvider() {
                    public ProviderDescriptor descriptor() { return descriptor; }
                    public Set<String> supportedMediaTypes() { return mediaTypes; }
                    public DocumentParseResult parse(DocumentParseRequest request, ProviderCallContext context)
                            throws ProviderException, InterruptedException {
                        return invoke(parser, declaration.documentParsing(), request, context, false);
                    }
                });
            }
            synchronized (gate) {
                providers = Map.copyOf(wrapped);
                if (state == State.STARTING) { state = State.RUNNING; }
            }
        } catch (Exception ex) {
            stopAccepting();
            if (ex instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            throw new PluginRuntimeException(PluginRuntimeException.Code.START_FAILED,
                    "plugin runtime failed to start", ex);
        } finally {
            startFinished.countDown();
        }
    }

    public Map<String, PluginProvider> providers() {
        synchronized (gate) { return providers; }
    }
    public State state() { synchronized (gate) { return state; } }

    public HealthSnapshot health() {
        synchronized (gate) { return new HealthSnapshot(runtimeId, health, consecutiveFailures, checkedAt); }
    }

    /** 显式整包复查；同一 Runtime 只允许一个检查，隔离后必须重启，禁用期间不恢复入口。 */
    public HealthSnapshot checkHealth(PluginHealthPlan plan) {
        Objects.requireNonNull(plan).validate(manifest);
        synchronized (gate) {
            if (state != State.RUNNING || isolated || checking) {
                throw new PluginHealthException("runtime cannot accept health check", null);
            }
            checking = true;
            health = Health.CHECKING;
        }
        var context = new ProviderCallContext("health-" + UUID.randomUUID(), Instant.now().plus(plan.budget()));
        try {
            for (var declaration : manifest.providers()) {
                for (var probe : plan.probes().get(declaration.id())) {
                    context.checkActive();
                    var result = invoke(targets.get(declaration.id()), declaration.documentParsing(),
                            probe.request(), context, true);
                    if (!probe.accepts().test(result)) {
                        throw new IllegalStateException("health assertion failed");
                    }
                    context.checkActive();
                }
            }
            synchronized (gate) {
                if (state != State.RUNNING || isolated) {
                    throw new IllegalStateException("runtime changed during health check");
                }
                health = Health.HEALTHY;
                healthBlocked = false;
                consecutiveFailures = 0;
                checkedAt = Instant.now();
                return health();
            }
        } catch (Exception | AssertionError ex) {
            synchronized (gate) {
                if (state == State.RUNNING && !isolated) { health = Health.UNHEALTHY; healthBlocked = true; }
                checkedAt = Instant.now();
            }
            if (ex instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            throw new PluginHealthException("plugin health verification failed", ex);
        } finally {
            synchronized (gate) { checking = false; }
        }
    }

    private void ensureAdmission(boolean probe) throws ProviderException {
        if (state != State.RUNNING || health == Health.ISOLATED || health == Health.INACTIVE
                || (!probe && (health == Health.CHECKING || health == Health.UNHEALTHY))) {
            throw unavailable("plugin runtime is not accepting calls");
        }
    }

    /** 只统计实际开始执行后的引擎故障；隔离是终态，晚到成功不得重新开放。 */
    private void recordOutcome(boolean failure, boolean probe) {
        synchronized (gate) {
            if (state != State.RUNNING || health == Health.ISOLATED) { return; }
            if (failure) {
                if (++consecutiveFailures >= FAILURE_THRESHOLD) { health = Health.ISOLATED; isolated = true; }
            } else if (!probe && health != Health.CHECKING) {
                consecutiveFailures = 0;
            }
        }
    }

    /** 只关闭入口和线程池提交，不调用插件代码，可在 Manager 的短临界区内执行。 */
    public void stopAccepting() {
        synchronized (gate) {
            if (state == State.STOPPED || state == State.STOP_FAILED) { return; }
            state = State.STOPPING;
            health = Health.INACTIVE;
            executor.shutdown(); // 已接收任务继续运行；新任务拒绝。
        }
    }

    /**
     * 等待启动返回及工作线程真实退出；false 表示仍需稍后重试，不表示已停止。
     * budget 限制排空等待，不强制限制 Session.close；适配器必须自行限制关闭 I/O。
     * 关闭失败保留资源所有权，并允许再次调用重试。
     */
    public boolean awaitStopped(Duration budget) throws InterruptedException {
        Objects.requireNonNull(budget);
        if (budget.isNegative() || budget.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("drain budget must be between zero and one day");
        }
        long nanos = budget.toNanos();
        long begin = System.nanoTime();
        stopAccepting();
        if (!startFinished.await(nanos, TimeUnit.NANOSECONDS)) { return false; }
        long left = Math.max(0, nanos - (System.nanoTime() - begin));
        if (!executor.awaitTermination(left, TimeUnit.NANOSECONDS)) { return false; }
        synchronized (cleanup) {
            if (state() == State.STOPPED) { return true; }
            try {
                if (session != null) { session.close(); }
                synchronized (gate) { state = State.STOPPED; }
                return true;
            } catch (Exception ex) {
                synchronized (gate) { state = State.STOP_FAILED; }
                if (ex instanceof InterruptedException) { Thread.currentThread().interrupt(); }
                throw new PluginRuntimeException(PluginRuntimeException.Code.STOP_FAILED,
                        "plugin runtime failed to release resources", ex);
            }
        }
    }

    private DocumentParseResult invoke(DocumentParserProvider target, DocumentParserDeclaration declaration,
                                       DocumentParseRequest request, ProviderCallContext context, boolean probe)
            throws ProviderException, InterruptedException {
        Objects.requireNonNull(request);
        Objects.requireNonNull(context);
        context.checkActive();
        var effective = resolve(declaration, request);
        synchronized (gate) {
            ensureAdmission(probe);
            if (!admissions.tryAcquire()) { throw unavailable("plugin runtime queue is full"); }
        }
        AtomicBoolean released = new AtomicBoolean();
        Runnable release = () -> { if (released.compareAndSet(false, true)) { admissions.release(); } };
        FutureTask<DocumentParseResult> task;
        AtomicBoolean targetStarted = new AtomicBoolean();
        try {
            // 在同步入口内关闭宿主流；异步任务只使用 Runtime 持有的有界快照。
            var owned = snapshot(effective, context);
            AtomicBoolean started = new AtomicBoolean();
            task = new FutureTask<>(() -> {
                context.checkActive(); // 排队也消耗同一个截止时间。
                synchronized (gate) {
                    // 隔离/复查失败后，之前排队的业务任务也不得进入引擎；优雅禁用仍排空已接收任务。
                    if (isolated || (!probe && healthBlocked)) {
                        throw unavailable("plugin runtime is isolated");
                    }
                    targetStarted.set(true);
                }
                var result = Objects.requireNonNull(target.parse(owned, context), "parse result");
                context.checkActive();
                return result;
            }) {
                @Override public void run() {
                    started.set(true);
                    try { super.run(); } finally { release.run(); }
                }
                @Override protected void done() {
                    // 队列中的取消立即归还槽位；正在执行的取消须等 run 真正退出。
                    if (!started.get()) { release.run(); }
                }
            };
            synchronized (gate) {
                ensureAdmission(probe);
                try { executor.execute(task); }
                catch (RejectedExecutionException ex) { throw unavailable("plugin runtime queue is full"); }
            }
        } catch (ProviderException | InterruptedException | RuntimeException | Error ex) {
            release.run();
            throw ex;
        }
        try {
            long nanos = remaining(context.deadline());
            var result = task.get(nanos, TimeUnit.NANOSECONDS);
            context.checkActive();
            recordOutcome(false, probe);
            return result;
        } catch (TimeoutException ex) {
            cancel(task);
            if (targetStarted.get()) { recordOutcome(true, probe); }
            throw new ProviderException(ProviderException.Code.DEADLINE_EXCEEDED, "provider call deadline exceeded");
        } catch (InterruptedException ex) {
            cancel(task);
            throw ex;
        } catch (ProviderException ex) {
            cancel(task);
            if (targetStarted.get()) { recordOutcome(true, probe); }
            throw ex;
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof ProviderException failure) {
                if (targetStarted.get() && (failure.code() == ProviderException.Code.UNAVAILABLE
                        || failure.code() == ProviderException.Code.INTERNAL_ERROR
                        || failure.code() == ProviderException.Code.DEADLINE_EXCEEDED)) { recordOutcome(true, probe); }
                throw failure;
            }
            if (cause instanceof InterruptedException interrupted) { throw interrupted; }
            if (targetStarted.get()) { recordOutcome(true, probe); }
            throw new ProviderException(ProviderException.Code.INTERNAL_ERROR, "provider execution failed", cause);
        }
    }

    private DocumentParseRequest snapshot(DocumentParseRequest request, ProviderCallContext context)
            throws ProviderException, InterruptedException {
        long limit = Math.min(request.maxInputBytes(), maxSnapshotBytes);
        try (var input = Objects.requireNonNull(request.content().openStream(), "document stream");
             var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int size;
            while (true) {
                context.checkActive();
                // 多读一个字节用于区分“恰好到达上限”和“超限”，不会静默截断。
                size = input.read(buffer, 0, (int) Math.min(buffer.length, limit - output.size() + 1));
                if (size == -1) { break; }
                if ((long) output.size() + size > limit) {
                    throw new ProviderException(ProviderException.Code.LIMIT_EXCEEDED, "document snapshot limit exceeded");
                }
                output.write(buffer, 0, size);
            }
            context.checkActive();
            byte[] bytes = output.toByteArray();
            return new DocumentParseRequest(request.documentId(), request.fileName(), request.mediaType(),
                    () -> new ByteArrayInputStream(bytes), request.maxInputBytes(), request.maxPages(), request.options());
        } catch (IOException | RuntimeException ex) {
            throw new ProviderException(ProviderException.Code.INPUT_READ_FAILED, "document snapshot read failed", ex);
        }
    }

    private void cancel(FutureTask<?> task) {
        task.cancel(true); // 仅请求中断；awaitTermination 才能判断执行线程是否真的退出。
        executor.remove(task); // 尚未开始的已取消任务不继续占用队列。
    }

    private static long remaining(Instant deadline) {
        try { return Math.max(0, Duration.between(Instant.now(), deadline).toNanos()); }
        catch (ArithmeticException ex) { return Long.MAX_VALUE; }
    }

    private static ProviderException unavailable(String message) {
        return new ProviderException(ProviderException.Code.UNAVAILABLE, message);
    }

    /** 单次明确选项覆盖 Manifest 默认；false 不等于未填写，不支持的显式选项直接报错。 */
    private static DocumentParseRequest resolve(DocumentParserDeclaration declaration, DocumentParseRequest request)
            throws ProviderException {
        if (!declaration.mediaTypes().contains(request.mediaType())) {
            throw new ProviderException(ProviderException.Code.UNSUPPORTED_MEDIA_TYPE, "unsupported document media type");
        }
        var options = request.options();
        var defaults = declaration.defaultOptions();
        String language = options.language() != null ? options.language() : defaults.language();
        boolean tables = options.recognizeTables() != null ? options.recognizeTables() : defaults.recognizeTables();
        boolean formulas = options.recognizeFormulas() != null ? options.recognizeFormulas() : defaults.recognizeFormulas();
        if ((language != null && !declaration.languages().contains(language))
                || (tables && !declaration.supportsTables()) || (formulas && !declaration.supportsFormulas())) {
            throw new ProviderException(ProviderException.Code.UNSUPPORTED_OPTION, "unsupported document parse option");
        }
        return new DocumentParseRequest(request.documentId(), request.fileName(), request.mediaType(),
                request.content(), request.maxInputBytes(), request.maxPages(),
                new DocumentParseOptions(language, tables, formulas));
    }
}
