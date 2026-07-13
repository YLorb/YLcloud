package com.ylcloud.handler;

import com.ylcloud.Exception.BaseException;
import com.ylcloud.Exception.ConflictException;
import com.ylcloud.Exception.ForbiddenException;
import com.ylcloud.Exception.NotFoundException;
import com.ylcloud.Exception.UnauthorizedException;
import com.ylcloud.Result;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsTypedBusinessExceptionsToHttpStatusAndBodyCode() {
        assertStatus(new BaseException("bad request"),400);
        assertStatus(new UnauthorizedException("unauthorized"),401);
        assertStatus(new ForbiddenException("forbidden"),403);
        assertStatus(new NotFoundException("not found"),404);
        assertStatus(new ConflictException("conflict"),409);
    }

    @Test
    void mapsUnexpectedExceptionsToHttp500() {
        ResponseEntity<Result<?>> response = handler.handleException(new IllegalStateException("boom"));

        assertEquals(500,response.getStatusCode().value());
        assertEquals(500,response.getBody().getCode());
    }

    private void assertStatus(BaseException exception, int expected) {
        ResponseEntity<Result<?>> response = handler.handleBaseException(exception);
        assertEquals(expected,response.getStatusCode().value());
        assertEquals(expected,response.getBody().getCode());
    }
}
