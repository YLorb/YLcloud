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

    /**
     * 初始化 SpaceController 对象。
     *
     * @param spaceService 空间服务
     */
    public SpaceController(SpaceService spaceService) {
        this.spaceService = spaceService;
    }

    /**
     * 创建 create 相关逻辑。
     *
     * @param dto 请求参数
     * @return 接口响应结果
     */
    @PostMapping
    public Result<SpaceVO> create(@RequestBody @Valid SpaceCreateDTO dto) {
        return Result.success(spaceService.createSpace(dto, BaseContext.getCurrentId()));
    }

    /**
     * 查询 listMySpaces 相关逻辑。
     * @return 接口响应结果
     */
    @GetMapping("/list")
    public Result<List<SpaceVO>> listMySpaces() {
        return Result.success(spaceService.listMySpaces(BaseContext.getCurrentId()));
    }

    /**
     * 查询 get 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @return 接口响应结果
     */
    @GetMapping("/{spaceId}")
    public Result<SpaceVO> get(@PathVariable Long spaceId) {
        return Result.success(spaceService.getSpace(spaceId,BaseContext.getCurrentId()));
    }

    /**
     * 更新 update 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param dto 请求参数
     * @return 接口响应结果
     */
    @PutMapping("/{spaceId}")
    public Result<SpaceVO> update(@PathVariable Long spaceId, @RequestBody @Valid SpaceUpdateDTO dto) {
        return Result.success(spaceService.updateSpace(spaceId,dto,BaseContext.getCurrentId()));
    }

    /**
     * 更新 updateVersionSetting 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param dto 请求参数
     * @return 接口响应结果
     */
    @PutMapping("/{spaceId}/version-setting")
    public Result<SpaceVO> updateVersionSetting(@PathVariable Long spaceId,
                                                @RequestBody @Valid SpaceVersionSettingDTO dto) {
        return Result.success(spaceService.updateVersionEnabled(spaceId,dto.getVersionEnabled(),BaseContext.getCurrentId()));
    }

    /**
     * 删除 delete 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @return 接口响应结果
     */
    @DeleteMapping("/{spaceId}")
    public Result<Boolean> delete(@PathVariable Long spaceId) {
        return Result.success(spaceService.deleteSpace(spaceId,BaseContext.getCurrentId()));
    }
}
