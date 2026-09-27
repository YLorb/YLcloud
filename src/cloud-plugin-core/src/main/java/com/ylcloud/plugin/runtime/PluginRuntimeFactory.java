package com.ylcloud.plugin.runtime;

import com.ylcloud.plugin.manifest.PluginManifest;
import com.ylcloud.plugin.spi.PluginProvider;
import java.util.Map;

/** 可信宿主提供的启动适配器。LOCAL 创建本地实现，DOCKER 创建容器/客户端适配器。
 * start 抛出异常前必须自行释放尚未移交的资源；返回 Session 后资源归 Runtime 所有。
 * 启动及关闭必须自行设置 I/O 超时；通用 Runtime 不能强杀任意 Java/本地代码。
 */
@FunctionalInterface
public interface PluginRuntimeFactory {
    Session start(PluginManifest manifest, PluginManifest.RuntimeMode mode) throws Exception;

    interface Session extends AutoCloseable {
        Map<String, ? extends PluginProvider> providers();
        /** 可重试且幂等；关闭失败保留 Session，宿主可再次等待停止来重试释放。 */
        @Override void close() throws Exception;
    }
}
