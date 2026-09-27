package com.ylcloud.plugin.registry;

import com.ylcloud.plugin.spi.PluginProvider;
import com.ylcloud.plugin.spi.ProviderDescriptor;
import com.ylcloud.plugin.spi.document.DocumentMediaType;
import com.ylcloud.plugin.spi.document.DocumentParserProvider;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 宿主侧的进程内 Provider 名册；注册不表示安装、启用、健康或获得业务访问权限。
 * 每个 ID 在本 Registry 中唯一，一条注册显式发布一种能力接口（可按其父能力查询）。
 * 一个插件包可使用不同 ID 提供多个能力；不自动发布实现类偶然实现的其他接口。
 *
 * <p>写入较少、读取较多：每次写入复制 Map 后以 CAS 原子发布，读操作只读取一个快照。
 * 元数据只在注册时调用一次并复制，不在查找过程中执行插件方法。
 * Provider 实例本身不被深拷贝，它的线程安全仍由实现负责。
 */
public final class ProviderRegistry {
    private final AtomicReference<Map<String, Registration>> entries = new AtomicReference<>(Map.of());

    /**
     * 注册已经创建好的实例，不发现插件、不启动进程、不调用 parse。
     * 先完整校验元数据，再一次性发布；重复 ID（包括相同实例/不同版本）均拒绝覆盖。
     * 返回的凭证属于这一次注册，后续撤销时用于避免旧实例误删新实例。
     */
    public <T extends PluginProvider> Registration register(Class<T> capability, T provider) {
        validateCapability(capability);
        Objects.requireNonNull(provider, "provider");
        if (!capability.isInstance(provider)) {
            throw new IllegalArgumentException("provider does not implement the declared capability");
        }
        Registration registration = capture(capability, provider);
        String id = registration.descriptor.id();
        while (true) {
            Map<String, Registration> current = entries.get();
            if (current.containsKey(id)) {
                throw new ProviderRegistryException(ProviderRegistryException.Code.DUPLICATE_ID,
                        "provider id is already registered: " + id);
            }
            Map<String, Registration> next = new HashMap<>(current);
            next.put(id, registration);
            // 只有 current 仍是当前快照才发布；失败则基于最新快照重试，保留其他并发写入。
            if (entries.compareAndSet(current, Map.copyOf(next))) {
                return registration;
            }
        }
    }

    /** 采集一次元数据但不发布；允许 Manager 在发布前核对 Manifest。 */
    public <T extends PluginProvider> PreparedRegistration prepare(Class<T> capability, T provider) {
        validateCapability(capability);
        Objects.requireNonNull(provider, "provider");
        if (!capability.isInstance(provider)) {
            throw new IllegalArgumentException("provider does not implement capability");
        }
        return new PreparedRegistration(this, capture(capability, provider));
    }

    /** 整批发布只有一个 CAS 生效点；任意冲突均不发布任何条目。每次生成新凭证。 */
    public List<Registration> registerAll(List<PreparedRegistration> prepared) {
        List<PreparedRegistration> inputs = List.copyOf(prepared);
        Map<String, Registration> additions = new HashMap<>();
        for (PreparedRegistration input : inputs) {
            if (input.owner != this) {
                throw new IllegalArgumentException("prepared registration belongs to another registry");
            }
            Registration source = input.snapshot;
            Registration fresh = new Registration(source.descriptor, source.capability,
                    source.provider, source.documentMediaTypes);
            if (additions.putIfAbsent(source.descriptor.id(), fresh) != null) {
                throw new ProviderRegistryException(ProviderRegistryException.Code.DUPLICATE_ID,
                        "duplicate provider id in batch");
            }
        }
        while (true) {
            Map<String, Registration> current = entries.get();
            Map<String, Registration> next = new HashMap<>(current);
            for (var entry : additions.entrySet()) {
                if (next.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
                    throw new ProviderRegistryException(ProviderRegistryException.Code.DUPLICATE_ID,
                            "provider id is already registered: " + entry.getKey());
                }
            }
            if (entries.compareAndSet(current, Map.copyOf(next))) {
                return additions.values().stream()
                        .sorted(Comparator.comparing(r -> r.descriptor.id())).toList();
            }
        }
    }

    /** 一次性撤销仍匹配的凭证；过期凭证跳过，不误删其他注册者的新实例。 */
    public int unregisterAll(List<Registration> registrations) {
        List<Registration> inputs = List.copyOf(registrations);
        while (true) {
            Map<String, Registration> current = entries.get();
            Map<String, Registration> next = new HashMap<>(current);
            int removed = 0;
            for (Registration registration : inputs) {
                if (next.remove(registration.descriptor.id(), registration)) {
                    removed++;
                }
            }
            if (removed == 0 || entries.compareAndSet(current, Map.copyOf(next))) {
                return removed;
            }
        }
    }

    /** 尚未发布的元数据快照，不能作为撤销凭证；重复发布会获得不同凭证。 */
    public static final class PreparedRegistration {
        private final ProviderRegistry owner;
        private final Registration snapshot;

        private PreparedRegistration(ProviderRegistry owner, Registration snapshot) {
            this.owner = owner;
            this.snapshot = snapshot;
        }

        public ProviderDescriptor descriptor() { return snapshot.descriptor; }
        public Class<? extends PluginProvider> capability() { return snapshot.capability; }
        public Set<String> documentMediaTypes() { return snapshot.documentMediaTypes; }
    }

    /** 不存在或没有发布所请求的能力时返回 empty；不回退到另一个 Provider。 */
    public <T extends PluginProvider> Optional<T> find(Class<T> capability, String id) {
        validateCapability(capability);
        validateId(id);
        Registration registration = entries.get().get(id);
        if (registration == null || !capability.isAssignableFrom(registration.capability)) {
            return Optional.empty();
        }
        return Optional.of(capability.cast(registration.provider));
    }

    /** 明确区分 ID 不存在和能力不匹配，并返回可直接按该能力接口使用的实例。 */
    public <T extends PluginProvider> T require(Class<T> capability, String id) {
        validateCapability(capability);
        validateId(id);
        Registration registration = entries.get().get(id);
        if (registration == null) {
            throw new ProviderRegistryException(ProviderRegistryException.Code.NOT_FOUND,
                    "provider is not registered: " + id);
        }
        if (!capability.isAssignableFrom(registration.capability)) {
            throw new ProviderRegistryException(ProviderRegistryException.Code.CAPABILITY_MISMATCH,
                    "provider has not published the requested capability: " + id);
        }
        return capability.cast(registration.provider);
    }

    /** 当前能力的不可变元数据列表，按 ID 排序；返回后不随注册表变化。 */
    public <T extends PluginProvider> List<ProviderDescriptor> list(Class<T> capability) {
        validateCapability(capability);
        Map<String, Registration> snapshot = entries.get();
        return snapshot.values().stream()
                .filter(entry -> capability.isAssignableFrom(entry.capability))
                .map(Registration::descriptor)
                .sorted(Comparator.comparing(ProviderDescriptor::id))
                .toList();
    }

    /**
     * 按具体 MIME 返回文档解析候选，类型规范化规则与请求一致。
     * 多个候选可共存，返回顺序只用于稳定展示，不代表优先级；不自动选择/回退。
     */
    public List<ProviderDescriptor> documentParsersFor(String mediaType) {
        String normalized = new DocumentMediaType(mediaType).value();
        Map<String, Registration> snapshot = entries.get();
        return snapshot.values().stream()
                .filter(entry -> DocumentParserProvider.class.isAssignableFrom(entry.capability))
                .filter(entry -> entry.documentMediaTypes.contains(normalized))
                .map(Registration::descriptor)
                .sorted(Comparator.comparing(ProviderDescriptor::id))
                .toList();
    }

    /**
     * 仅撤销这次注册；重复撤销、其他 Registry 的凭证、已被替换的旧凭证返回 false。
     * 不关闭 Provider，也不取消已取得实例的调用；调用排空与资源释放由后续 Manager/Runtime 负责。
     */
    public boolean unregister(Registration registration) {
        Objects.requireNonNull(registration, "registration");
        String id = registration.descriptor.id();
        while (true) {
            Map<String, Registration> current = entries.get();
            if (current.get(id) != registration) {
                return false;
            }
            Map<String, Registration> next = new HashMap<>(current);
            next.remove(id);
            if (entries.compareAndSet(current, Map.copyOf(next))) {
                return true;
            }
        }
    }

    private static Registration capture(Class<? extends PluginProvider> capability, PluginProvider provider) {
        try {
            ProviderDescriptor descriptor = Objects.requireNonNull(provider.descriptor(), "descriptor");
            Set<String> mediaTypes = Set.of();
            if (DocumentParserProvider.class.isAssignableFrom(capability)) {
                mediaTypes = Set.copyOf(((DocumentParserProvider) provider).supportedMediaTypes());
                if (mediaTypes.isEmpty()) {
                    throw new IllegalArgumentException("document provider must declare supported media types");
                }
                for (String mediaType : mediaTypes) {
                    if (!new DocumentMediaType(mediaType).value().equals(mediaType)) {
                        throw new IllegalArgumentException("provider media types must be canonical lowercase values");
                    }
                }
            }
            return new Registration(descriptor, capability, provider, mediaTypes);
        } catch (RuntimeException ex) {
            // 元数据回调可能抛出包含内部细节的异常；对外只给稳定错误，原因留给受控诊断。
            throw new ProviderRegistryException(ProviderRegistryException.Code.INVALID_METADATA,
                    "provider registration metadata is invalid", ex);
        }
    }

    private static void validateCapability(Class<?> capability) {
        Objects.requireNonNull(capability, "capability");
        if (!capability.isInterface() || capability == PluginProvider.class
                || !PluginProvider.class.isAssignableFrom(capability)) {
            throw new IllegalArgumentException("capability must be a business interface extending PluginProvider");
        }
    }

    private static void validateId(String id) {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
    }

    /**
     * 不可自行构造的注册凭证，同时提供本次注册的元数据快照。
     * documentMediaTypes 仅对文档解析能力有意义，其他能力为空集合。
     */
    public static final class Registration {
        private final ProviderDescriptor descriptor;
        private final Class<? extends PluginProvider> capability;
        private final PluginProvider provider;
        private final Set<String> documentMediaTypes;

        private Registration(ProviderDescriptor descriptor, Class<? extends PluginProvider> capability,
                             PluginProvider provider, Set<String> documentMediaTypes) {
            this.descriptor = descriptor;
            this.capability = capability;
            this.provider = provider;
            this.documentMediaTypes = Set.copyOf(documentMediaTypes);
        }

        public ProviderDescriptor descriptor() { return descriptor; }
        public Class<? extends PluginProvider> capability() { return capability; }
        public Set<String> documentMediaTypes() { return documentMediaTypes; }
    }
}
