package com.ylcloud.async.task;

public class StaleTaskException extends RuntimeException {
    public StaleTaskException(String message) {
        super(message);
    }
}
