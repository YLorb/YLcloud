package com.ylcloud.controller;

import com.ylcloud.DTO.SpaceMemberAddDTO;
import com.ylcloud.DTO.SpaceMemberRoleDTO;
import com.ylcloud.Result;
import com.ylcloud.VO.SpaceMemberVO;
import com.ylcloud.context.BaseContext;
import com.ylcloud.service.SpaceMemberService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 空间成员控制器。
 */
@RestController
@RequestMapping("/api/space/{spaceId}/members")
public class SpaceMemberController {
    private final SpaceMemberService spaceMemberService;

    /**
     * 初始化 SpaceMemberController 对象。
     *
     * @param spaceMemberService 方法入参
     */
    public SpaceMemberController(SpaceMemberService spaceMemberService) {
        this.spaceMemberService = spaceMemberService;
    }

    /**
     * 查询 list 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @return 接口响应结果
     */
    @GetMapping
    public Result<List<SpaceMemberVO>> list(@PathVariable Long spaceId) {
        return Result.success(spaceMemberService.listMembers(spaceId,BaseContext.getCurrentId()));
    }

    /**
     * 新增 add 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param dto 请求参数
     * @return 接口响应结果
     */
    @PostMapping
    public Result<Boolean> add(@PathVariable Long spaceId, @RequestBody @Valid SpaceMemberAddDTO dto) {
        return Result.success(spaceMemberService.addMember(spaceId,dto,BaseContext.getCurrentId()));
    }

    /**
     * 更新 updateRole 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param userId 用户 ID
     * @param dto 请求参数
     * @return 接口响应结果
     */
    @PutMapping("/{userId}/role")
    public Result<Boolean> updateRole(@PathVariable Long spaceId,
                                      @PathVariable Long userId,
                                      @RequestBody @Valid SpaceMemberRoleDTO dto) {
        return Result.success(spaceMemberService.updateRole(spaceId,userId,dto,BaseContext.getCurrentId()));
    }

    /**
     * 移除 remove 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param userId 用户 ID
     * @return 接口响应结果
     */
    @DeleteMapping("/{userId}")
    public Result<Boolean> remove(@PathVariable Long spaceId, @PathVariable Long userId) {
        return Result.success(spaceMemberService.removeMember(spaceId,userId,BaseContext.getCurrentId()));
    }
}
