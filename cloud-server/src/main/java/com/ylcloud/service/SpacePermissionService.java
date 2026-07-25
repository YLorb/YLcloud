package com.ylcloud.service;

import com.ylcloud.Exception.ForbiddenException;
import com.ylcloud.authorization.AccessSubject;
import com.ylcloud.authorization.ResourceAction;
import com.ylcloud.authorization.ResourceType;
import com.ylcloud.constant.SpaceConstant;
import com.ylcloud.entity.SpaceMember;
import com.ylcloud.mapper.SpaceMemberMapper;
import org.springframework.stereotype.Service;

/**
 * 空间权限校验服务。
 */
@Service
public class SpacePermissionService {
    private final SpaceMemberMapper spaceMemberMapper;
    private final AuthorizationService authorizationService;

    /**
     * 初始化 SpacePermissionService 对象。
     *
     * @param spaceMemberMapper 方法入参
     */
    public SpacePermissionService(SpaceMemberMapper spaceMemberMapper,
                                  AuthorizationService authorizationService) {
        this.spaceMemberMapper = spaceMemberMapper;
        this.authorizationService = authorizationService;
    }

    /**
     * 校验 requireMember 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param userId 用户 ID
     * @return 处理结果
     */
    public SpaceMember requireMember(Long spaceId, Long userId) {
        SpaceMember member = spaceMemberMapper.getActive(spaceId,userId);
        if(member == null) {
            authorizationService.require(
                    AccessSubject.user(userId),
                    ResourceType.SPACE,
                    spaceId,
                    ResourceAction.READ
            );
            return syntheticMember(spaceId,userId,SpaceConstant.ROLE_MEMBER);
        }
        return member;
    }

    /**
     * 校验 requireAdmin 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param userId 用户 ID
     * @return 处理结果
     */
    public SpaceMember requireAdmin(Long spaceId, Long userId) {
        SpaceMember member = spaceMemberMapper.getActive(spaceId,userId);
        if(member != null && (SpaceConstant.ROLE_OWNER.equals(member.getRole())
                || SpaceConstant.ROLE_ADMIN.equals(member.getRole()))) {
            return member;
        }
        authorizationService.require(
                AccessSubject.user(userId),
                ResourceType.SPACE,
                spaceId,
                ResourceAction.MANAGE
        );
        return syntheticMember(spaceId,userId,SpaceConstant.ROLE_ADMIN);
    }

    /**
     * 校验 requireOwner 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param userId 用户 ID
     * @return 处理结果
     */
    public SpaceMember requireOwner(Long spaceId, Long userId) {
        SpaceMember member = requireMember(spaceId,userId);
        if(!SpaceConstant.ROLE_OWNER.equals(member.getRole())) {
            throw new ForbiddenException("只有空间所有者可以执行该操作");
        }
        return member;
    }

    /**
     * 执行 isOwnerRole 函数的业务处理。
     *
     * @param role 角色
     * @return 处理结果
     */
    public boolean isOwnerRole(String role) {
        return SpaceConstant.ROLE_OWNER.equals(role);
    }

    /**
     * 执行 isAdminRole 函数的业务处理。
     *
     * @param role 角色
     * @return 处理结果
     */
    public boolean isAdminRole(String role) {
        return SpaceConstant.ROLE_OWNER.equals(role) || SpaceConstant.ROLE_ADMIN.equals(role);
    }

    private SpaceMember syntheticMember(Long spaceId, Long userId, String role) {
        SpaceMember member = new SpaceMember();
        member.setSpaceId(spaceId);
        member.setUserId(userId);
        member.setRole(role);
        member.setStatus(1);
        return member;
    }
}
