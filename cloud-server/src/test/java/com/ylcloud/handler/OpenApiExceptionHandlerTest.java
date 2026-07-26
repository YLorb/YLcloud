package com.ylcloud.handler;

import com.ylcloud.VO.OpenApiErrorEnvelope;
import com.ylcloud.interceptor.OpenApiKeyInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenApiExceptionHandlerTest {
    @Test
    void malformedJsonUsesStableClientError() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(OpenApiKeyInterceptor.TRACE_ATTRIBUTE,"trace_1234");
        OpenApiExceptionHandler handler = new OpenApiExceptionHandler();

        ResponseEntity<OpenApiErrorEnvelope> response = handler.malformedBody(
                new HttpMessageNotReadableException("broken",new MockHttpInputMessage(new byte[0])),request);

        assertEquals(400,response.getStatusCode().value());
        assertEquals("MALFORMED_JSON",response.getBody().error().code());
        assertEquals("trace_1234",response.getBody().meta().traceId());
    }
}
