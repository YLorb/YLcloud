package com.ylcloud;

public class BaseException extends RuntimeException {
    public BaseException() {
        super();
    }
    public BaseException(String meseage) {
        super(meseage);
    }
}
