package com.ylcloud.Exception;

public class UnauthorizedException extends BaseException {
    public UnauthorizedException(String message) {
        super(401, message);
    }
}
