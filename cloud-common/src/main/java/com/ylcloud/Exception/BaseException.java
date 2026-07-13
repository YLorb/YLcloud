package com.ylcloud.Exception;

public class BaseException extends RuntimeException {
    private final int statusCode;

    public BaseException() {
        this(400, null, null);
    }

    public BaseException(String message) {
        this(400, message, null);
    }

    public BaseException(String message, Throwable cause) {
        this(400, message, cause);
    }

    public BaseException(int statusCode, String message) {
        this(statusCode, message, null);
    }

    public BaseException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
