package com.ylcloud.controller;

import com.ylcloud.service.PreviewLinkService;
import com.ylcloud.service.ShortLinkAccessLog;
import com.ylcloud.context.BaseContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PreviewLinkController {
    private final PreviewLinkService links;
    private final ShortLinkAccessLog accessLog;

    public PreviewLinkController(PreviewLinkService links, ShortLinkAccessLog accessLog) {
        this.links = links;
        this.accessLog = accessLog;
    }

    @GetMapping("/api/preview-links/{code}")
    public ResponseEntity<Void> resolve(@PathVariable String code, HttpServletRequest request) {
        String target = links.resolve(code);
        accessLog.record("PREVIEW_RESOLVE", BaseContext.getCurrentId(), request.getRemoteAddr(), "/p/" + code,
                code, null, "RESOLVED");
        return ResponseEntity.status(302)
                .header(HttpHeaders.LOCATION, target)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .build();
    }
}
