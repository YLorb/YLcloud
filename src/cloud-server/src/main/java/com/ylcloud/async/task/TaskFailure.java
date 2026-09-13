package com.ylcloud.async.task;

public record TaskFailure(String type, String code, String message, boolean retryable, boolean stale) {
}
