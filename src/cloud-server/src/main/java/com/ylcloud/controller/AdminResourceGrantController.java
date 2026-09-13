package com.ylcloud.controller;

import com.ylcloud.DTO.AdminResourceGrantCreateDTO;
import com.ylcloud.Result;
import com.ylcloud.VO.AdminResourceGrantVO;
import com.ylcloud.service.AdminResourceGrantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/resource-grants")
@RequiredArgsConstructor
public class AdminResourceGrantController {
    private final AdminResourceGrantService grantService;

    @PostMapping
    public Result<List<AdminResourceGrantVO>> grant(
            @RequestBody @Valid AdminResourceGrantCreateDTO dto
    ) {
        return Result.success(grantService.grant(dto));
    }

    @GetMapping("/mine")
    public Result<List<AdminResourceGrantVO>> grantedByMe() {
        return Result.success(grantService.listGrantedByMe());
    }

    @GetMapping("/received")
    public Result<List<AdminResourceGrantVO>> received() {
        return Result.success(grantService.listReceived());
    }

    @DeleteMapping("/{grantId}")
    public Result<Boolean> revoke(@PathVariable Long grantId) {
        grantService.revoke(grantId);
        return Result.success(true);
    }
}
