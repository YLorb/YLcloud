package com.ylcloud.Exception;

public class ForbiddenException extends BaseException {
    public ForbiddenException(String message) {
        super(403, message);
    }
}
