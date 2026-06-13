package com.ylcloud.controller;

import com.ylcloud.DTO.SpaceCreateDTO;
import com.ylcloud.DTO.SpaceUpdateDTO;
import com.ylcloud.DTO.SpaceVersionSettingDTO;
import com.ylcloud.Result;
import com.ylcloud.VO.SpaceVO;
import com.ylcloud.context.BaseContext;
import com.ylcloud.service.SpaceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 空间基础控制器。
 */
@RestController
@RequestMapping("/api/space")
public class SpaceController {
    private final SpaceService spaceService;

    public SpaceController(SpaceService spaceService) {
        this.spaceService = spaceService;
    }

    @PostMapping
    public Result<SpaceVO> create(@RequestBody @Valid SpaceCreateDTO dto) {
        return Result.success(spaceService.createSpace(dto, BaseContext.getCurrentId()));
    }

    @GetMapping("/list")
    public Result<List<SpaceVO>> listMySpaces() {
        return Result.success(spaceService.listMySpaces(BaseContext.getCurrentId()));
    }

    @GetMapping("/{spaceId}")
    public Result<SpaceVO> get(@PathVariable Long spaceId) {
        return Result.success(spaceService.getSpace(spaceId,BaseContext.getCurrentId()));
    }

    @PutMapping("/{spaceId}")
    public Result<SpaceVO> update(@PathVariable Long spaceId, @RequestBody @Valid SpaceUpdateDTO dto) {
        return Result.success(spaceService.updateSpace(spaceId,dto,BaseContext.getCurrentId()));
    }

    @PutMapping("/{spaceId}/version-setting")
    public Result<SpaceVO> updateVersionSetting(@PathVariable Long spaceId,
                                                @RequestBody @Valid SpaceVersionSettingDTO dto) {
        return Result.success(spaceService.updateVersionEnabled(spaceId,dto.getVersionEnabled(),BaseContext.getCurrentId()));
    }

    @DeleteMapping("/{spaceId}")
    public Result<Boolean> delete(@PathVariable Long spaceId) {
        return Result.success(spaceService.deleteSpace(spaceId,BaseContext.getCurrentId()));
    }
}
