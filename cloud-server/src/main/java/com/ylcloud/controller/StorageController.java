package com.ylcloud.controller;

import com.ylcloud.Result;
import com.ylcloud.VO.StorageQuotaVO;
import com.ylcloud.context.BaseContext;
import com.ylcloud.service.StorageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/storage")
public class StorageController {
    private final StorageService storageService;

    public StorageController(StorageService storageService) {
        this.storageService = storageService;
    }

    @GetMapping("/quota")
    public Result<StorageQuotaVO> quota() {
        return Result.success(storageService.quota(BaseContext.getCurrentId()));
    }
}
