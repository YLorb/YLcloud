package com.ylcloud;

import lombok.Data;

@Data
public class Result<T> {
    private Integer code;
    private String message;
    private T data;

    public Result(T data) {
        this.code = 200;
        this.message = "success";
        this.data = data;
    }
    /*
        success = 1：成功，
        success = 0：失败，可以自行定义错误的状态码和信息
     */
    public Result(T data,boolean success,String message) {
        if(success) {
            this.code = 200; this.message = "success";
        }
        else {
            this.code = 500;
            this.message = message;
        }
        this.data = data;
    }
    /*
        自定义状态码和提示信息
     */
    public Result(int code,String message) {
        this.code = code;
        this.message = message; // 提示信息，对接口调用结果进行描述
        this.data = null; // 泛型数据，表示接口调用返回的数据
    }

    public static <T> Result <T> success(T data) {
        return new Result<>(data);
    }

    public static Result<Void> success() {
        return new Result<>(null);
    }

    public static <T> Result<T> error(String message) {
        return new Result<>(500,message);
    }

    public static <T> Result<T> error(int code,String message) {
        return new Result<>(code ,message);
    }
}
