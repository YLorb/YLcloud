package com.ylcloud.handler;

import com.ylcloud.Exception.BaseException;
import com.ylcloud.VO.OpenApiErrorEnvelope;
import com.ylcloud.VO.OpenApiMeta;
import com.ylcloud.controller.OpenApiController;
import com.ylcloud.interceptor.OpenApiKeyInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = OpenApiController.class)
@Slf4j
public class OpenApiExceptionHandler {
    @ExceptionHandler(BaseException.class)
    public ResponseEntity<OpenApiErrorEnvelope> business(BaseException exception,HttpServletRequest request) {
        return response(exception.getStatusCode(),code(exception.getStatusCode()),exception.getMessage(),Map.of(),request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<OpenApiErrorEnvelope> validation(MethodArgumentNotValidException exception,HttpServletRequest request) {
        FieldError field = exception.getBindingResult().getFieldError();
        String message = field == null ? "请求参数不合法" : field.getDefaultMessage();
        return response(400,"VALIDATION_ERROR",message,field == null ? Map.of() : Map.of("field",field.getField()),request);
    }

    @ExceptionHandler({MissingRequestHeaderException.class,MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<OpenApiErrorEnvelope> binding(Exception exception,HttpServletRequest request) {
        return response(400,"VALIDATION_ERROR","请求参数或请求头不完整",Map.of(),request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<OpenApiErrorEnvelope> malformedBody(HttpMessageNotReadableException exception,
                                                               HttpServletRequest request) {
        return response(400,"MALFORMED_JSON","请求体不是合法 JSON",Map.of(),request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<OpenApiErrorEnvelope> tooLarge(MaxUploadSizeExceededException exception,HttpServletRequest request) {
        return response(413,"PAYLOAD_TOO_LARGE","上传内容超过站点限制",Map.of(),request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<OpenApiErrorEnvelope> unexpected(Exception exception,HttpServletRequest request) {
        log.error("open API request failed traceId={}",request.getAttribute(OpenApiKeyInterceptor.TRACE_ATTRIBUTE),exception);
        return response(500,"INTERNAL_ERROR","服务暂时不可用",Map.of(),request);
    }

    private ResponseEntity<OpenApiErrorEnvelope> response(int status,String code,String message,Map<String,Object> details,
                                                           HttpServletRequest request) {
        String trace = String.valueOf(request.getAttribute(OpenApiKeyInterceptor.TRACE_ATTRIBUTE));
        boolean alias = Boolean.TRUE.equals(request.getAttribute(OpenApiKeyInterceptor.UNVERSIONED_ATTRIBUTE));
        OpenApiErrorEnvelope body = new OpenApiErrorEnvelope(
                new OpenApiErrorEnvelope.OpenApiError(code,message == null ? "请求失败" : message,details),
                new OpenApiMeta(trace,"v1","v1",alias));
        return ResponseEntity.status(status).body(body);
    }

    private String code(int status) {
        return switch(status) {
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 409 -> "CONFLICT";
            case 410 -> "API_VERSION_READ_ONLY";
            case 413 -> "PAYLOAD_TOO_LARGE";
            case 429 -> "RATE_LIMITED";
            default -> "INVALID_REQUEST";
        };
    }
}
