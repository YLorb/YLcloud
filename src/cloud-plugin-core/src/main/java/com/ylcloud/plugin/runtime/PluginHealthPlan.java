package com.ylcloud.plugin.runtime;

import com.ylcloud.plugin.manifest.PluginManifest;
import com.ylcloud.plugin.spi.document.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Predicate;

/** 宿主提供可信、无敏感数据的固定样例和结果断言，不接受插件自行报告成功。 */
public record PluginHealthPlan(Map<String, List<Probe>> probes, Duration budget) {
    public record Probe(DocumentParseRequest request, Predicate<DocumentParseResult> accepts) {
        public Probe { Objects.requireNonNull(request); Objects.requireNonNull(accepts); }
    }
    public PluginHealthPlan {
        Objects.requireNonNull(budget);
        if (budget.isNegative() || budget.isZero() || budget.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("health budget must be positive and at most five minutes");
        }
        Map<String, List<Probe>> copy = new HashMap<>();
        probes.forEach((id, values) -> copy.put(Objects.requireNonNull(id), List.copyOf(values)));
        probes = Map.copyOf(copy);
        if (probes.values().stream().mapToInt(List::size).sum() > 256) {
            throw new IllegalArgumentException("too many health probes");
        }
    }

    /** 检查样例配置覆盖声明；覆盖不代表模型正确，真正结果由宿主断言验证。 */
    public void validate(PluginManifest manifest) {
        Set<String> ids = new HashSet<>();
        manifest.providers().forEach(p -> ids.add(p.id()));
        if (!probes.keySet().equals(ids)) { throw new IllegalArgumentException("health provider set mismatch"); }
        for (var provider : manifest.providers()) {
            var declaration = provider.documentParsing();
            Set<String> media = new HashSet<>();
            Set<String> languages = new HashSet<>();
            boolean tables = false, formulas = false;
            var cases = probes.get(provider.id());
            if (cases.isEmpty()) { throw new IllegalArgumentException("missing provider probes"); }
            for (var probe : cases) {
                var options = probe.request().options();
                var defaults = declaration.defaultOptions();
                media.add(probe.request().mediaType());
                String language = options.language() == null ? defaults.language() : options.language();
                if (language != null) { languages.add(language); }
                tables |= options.recognizeTables() == null ? defaults.recognizeTables() : options.recognizeTables();
                formulas |= options.recognizeFormulas() == null ? defaults.recognizeFormulas() : options.recognizeFormulas();
            }
            if (!media.equals(declaration.mediaTypes()) || !languages.equals(declaration.languages())
                    || (declaration.supportsTables() && !tables) || (declaration.supportsFormulas() && !formulas)) {
                throw new IllegalArgumentException("health probes do not cover declared capabilities");
            }
        }
    }
}
