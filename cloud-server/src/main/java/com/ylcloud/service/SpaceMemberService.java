package com.ylcloud.service;

import com.ylcloud.DTO.SpaceMemberAddDTO;
import com.ylcloud.DTO.SpaceMemberRoleDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.Exception.ForbiddenException;
import com.ylcloud.VO.SpaceMemberVO;
import com.ylcloud.constant.SpaceConstant;
import com.ylcloud.entity.SpaceMember;
import com.ylcloud.entity.Space;
import com.ylcloud.mapper.SpaceMapper;
import com.ylcloud.mapper.SpaceMemberMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 空间成员业务服务。
 */
@Service
public class SpaceMemberService {
    private final SpaceMemberMapper spaceMemberMapper;
    private final SpacePermissionService spacePermissionService;
    private final SpaceMapper spaceMapper;

    /**
     * 初始化 SpaceMemberService 对象。
     *
     * @param spaceMemberMapper 方法入参
     * @param spacePermissionService 方法入参
     */
    public SpaceMemberService(SpaceMemberMapper spaceMemberMapper,
                              SpacePermissionService spacePermissionService,
                              SpaceMapper spaceMapper) {
        this.spaceMemberMapper = spaceMemberMapper;
        this.spacePermissionService = spacePermissionService;
        this.spaceMapper = spaceMapper;
    }

    /**
     * 查询 listMembers 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param userId 用户 ID
     * @return 列表结果
     */
    public List<SpaceMemberVO> listMembers(Long spaceId, Long userId) {
        spacePermissionService.requireMember(spaceId,userId);
        return spaceMemberMapper.listBySpaceId(spaceId);
    }

    /**
     * 新增 addMember 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param dto 请求参数
     * @param operatorId 操作人 ID
     * @return 处理结果
     */
    @Transactional
    public Boolean addMember(Long spaceId, SpaceMemberAddDTO dto, Long operatorId) {
        requireTeam(spaceId);
        spacePermissionService.requireAdmin(spaceId,operatorId);
        if(spaceMemberMapper.getActive(spaceId,dto.getUserId()) != null) {
            throw new BaseException("用户已在该空间中");
        }
        String role = normalizeRole(dto.getRole());
        if(SpaceConstant.ROLE_OWNER.equals(role)) {
            throw new BaseException("不能直接添加所有者");
        }
        if(SpaceConstant.ROLE_ADMIN.equals(role)) {
            spacePermissionService.requireOwner(spaceId,operatorId);
        }

        LocalDateTime now = LocalDateTime.now();
        spaceMemberMapper.activate(spaceId,dto.getUserId(),role,now);
        return true;
    }

    /**
     * 更新 updateRole 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param targetUserId 目标用户 ID
     * @param dto 请求参数
     * @param operatorId 操作人 ID
     * @return 处理结果
     */
    @Transactional
    public Boolean updateRole(Long spaceId, Long targetUserId, SpaceMemberRoleDTO dto, Long operatorId) {
        requireTeam(spaceId);
        spacePermissionService.requireOwner(spaceId,operatorId);
        SpaceMember target = spaceMemberMapper.getActive(spaceId,targetUserId);
        if(target == null) {
            throw new BaseException("成员不存在");
        }
        if(SpaceConstant.ROLE_OWNER.equals(target.getRole())) {
            throw new BaseException("不能修改所有者角色");
        }
        String role = normalizeRole(dto.getRole());
        if(SpaceConstant.ROLE_OWNER.equals(role)) {
            throw new BaseException("暂不支持转让所有者");
        }
        int rows = spaceMemberMapper.updateRole(spaceId,targetUserId,role,LocalDateTime.now());
        if(rows == 0) {
            throw new BaseException("角色更新失败");
        }
        return true;
    }

    /**
     * 移除 removeMember 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param targetUserId 目标用户 ID
     * @param operatorId 操作人 ID
     * @return 处理结果
     */
    @Transactional
    public Boolean removeMember(Long spaceId, Long targetUserId, Long operatorId) {
        requireTeam(spaceId);
        SpaceMember operator = spacePermissionService.requireAdmin(spaceId,operatorId);
        SpaceMember target = spaceMemberMapper.getActive(spaceId,targetUserId);
        if(target == null) {
            throw new BaseException("成员不存在");
        }
        if(SpaceConstant.ROLE_OWNER.equals(target.getRole())) {
            throw new BaseException("不能移除空间所有者");
        }
        if(SpaceConstant.ROLE_ADMIN.equals(target.getRole()) && !SpaceConstant.ROLE_OWNER.equals(operator.getRole())) {
            throw new BaseException("只有所有者可以移除管理员");
        }
        int rows = spaceMemberMapper.disable(spaceId,targetUserId,LocalDateTime.now());
        if(rows == 0) {
            throw new BaseException("成员移除失败");
        }
        return true;
    }

    private void requireTeam(Long spaceId) {
        Space space = spaceMapper.getById(spaceId);
        if(space == null) {
            throw new BaseException("空间不存在或不处于可操作状态");
        }
        if(SpaceConstant.TYPE_PERSONAL.equals(space.getType())) {
            throw new ForbiddenException("PERSONAL Space 永久私有，不能管理成员");
        }
    }

    /**
     * 规范化 normalizeRole 相关逻辑。
     *
     * @param role 角色
     * @return 处理结果
     */
    private String normalizeRole(String role) {
        if(role == null || role.isBlank()) {
            return SpaceConstant.ROLE_MEMBER;
        }
        String normalized = role.trim().toUpperCase();
        if(!SpaceConstant.ROLE_MEMBER.equals(normalized) && !SpaceConstant.ROLE_ADMIN.equals(normalized) && !SpaceConstant.ROLE_OWNER.equals(normalized)) {
            throw new BaseException("空间角色不合法");
        }
        return normalized;
    }
}
