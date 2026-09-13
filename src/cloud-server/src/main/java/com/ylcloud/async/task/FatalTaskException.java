package com.ylcloud.async.task;

public class FatalTaskException extends RuntimeException {
    public FatalTaskException(String message) {
        super(message);
    }
}
