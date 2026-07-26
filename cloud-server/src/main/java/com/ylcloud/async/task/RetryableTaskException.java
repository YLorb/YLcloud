package com.ylcloud.async.task;

public class RetryableTaskException extends RuntimeException {
    public RetryableTaskException(String message) {
        super(message);
    }
}
