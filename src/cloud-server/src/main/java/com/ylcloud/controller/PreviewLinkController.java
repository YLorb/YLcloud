package com.ylcloud.controller;

import com.ylcloud.service.PreviewLinkService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PreviewLinkController {
    private final PreviewLinkService links;

    public PreviewLinkController(PreviewLinkService links) {
        this.links = links;
    }

    @GetMapping("/api/preview-links/{code}")
    public ResponseEntity<Void> resolve(@PathVariable String code) {
        return ResponseEntity.status(302)
                .header(HttpHeaders.LOCATION, links.resolve(code))
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .build();
    }
}
