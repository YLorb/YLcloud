package com.ylcloud.controller;

import com.ylcloud.share.ShareLinkController;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/share")
public class ShareController {
    /** Old codes never resolve to new links, including preview/stream/download variants. */
    @RequestMapping({"", "/**"})
    public void expired(HttpServletResponse response) throws java.io.IOException {
        ShareLinkController.expiredHtml(response);
    }
}
