package com.ylcloud.service;

import com.ylcloud.Exception.BaseException;
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

    public SpacePermissionService(SpaceMemberMapper spaceMemberMapper) {
        this.spaceMemberMapper = spaceMemberMapper;
    }

    public SpaceMember requireMember(Long spaceId, Long userId) {
        SpaceMember member = spaceMemberMapper.getActive(spaceId,userId);
        if(member == null) {
            throw new BaseException("没有空间访问权限");
        }
        return member;
    }

    public SpaceMember requireAdmin(Long spaceId, Long userId) {
        SpaceMember member = requireMember(spaceId,userId);
        if(!SpaceConstant.ROLE_OWNER.equals(member.getRole()) && !SpaceConstant.ROLE_ADMIN.equals(member.getRole())) {
            throw new BaseException("没有空间管理权限");
        }
        return member;
    }

    public SpaceMember requireOwner(Long spaceId, Long userId) {
        SpaceMember member = requireMember(spaceId,userId);
        if(!SpaceConstant.ROLE_OWNER.equals(member.getRole())) {
            throw new BaseException("只有空间所有者可以执行该操作");
        }
        return member;
    }

    public boolean isOwnerRole(String role) {
        return SpaceConstant.ROLE_OWNER.equals(role);
    }

    public boolean isAdminRole(String role) {
        return SpaceConstant.ROLE_OWNER.equals(role) || SpaceConstant.ROLE_ADMIN.equals(role);
    }
}
