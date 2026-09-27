package com.ylcloud.plugin.runtime;

/** 固定对外错误；样例内容、断言及引擎异常只保留在受控原因链中。 */
public final class PluginHealthException extends RuntimeException {
    public PluginHealthException(String message, Throwable cause) { super(message, cause); }
}
