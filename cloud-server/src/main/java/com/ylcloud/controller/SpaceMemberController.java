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

    public SpaceMemberController(SpaceMemberService spaceMemberService) {
        this.spaceMemberService = spaceMemberService;
    }

    @GetMapping
    public Result<List<SpaceMemberVO>> list(@PathVariable Long spaceId) {
        return Result.success(spaceMemberService.listMembers(spaceId,BaseContext.getCurrentId()));
    }

    @PostMapping
    public Result<Boolean> add(@PathVariable Long spaceId, @RequestBody @Valid SpaceMemberAddDTO dto) {
        return Result.success(spaceMemberService.addMember(spaceId,dto,BaseContext.getCurrentId()));
    }

    @PutMapping("/{userId}/role")
    public Result<Boolean> updateRole(@PathVariable Long spaceId,
                                      @PathVariable Long userId,
                                      @RequestBody @Valid SpaceMemberRoleDTO dto) {
        return Result.success(spaceMemberService.updateRole(spaceId,userId,dto,BaseContext.getCurrentId()));
    }

    @DeleteMapping("/{userId}")
    public Result<Boolean> remove(@PathVariable Long spaceId, @PathVariable Long userId) {
        return Result.success(spaceMemberService.removeMember(spaceId,userId,BaseContext.getCurrentId()));
    }
}
