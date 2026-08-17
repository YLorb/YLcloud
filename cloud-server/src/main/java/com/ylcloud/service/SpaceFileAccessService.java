package com.ylcloud.service;

import com.ylcloud.Exception.ForbiddenException;
import com.ylcloud.Exception.NotFoundException;
import com.ylcloud.VO.SpaceFileCapabilityVO;
import com.ylcloud.authorization.SpaceFileAction;
import com.ylcloud.constant.SpaceConstant;
import com.ylcloud.entity.SpaceFile;
import com.ylcloud.entity.SpaceMember;
import com.ylcloud.mapper.SpaceFileMapper;
import org.springframework.stereotype.Service;

/** Central capability boundary for Space file operations. */
@Service
public class SpaceFileAccessService {
    private final SpacePermissionService permissionService;
    private final SpaceFileMapper fileMapper;

    public SpaceFileAccessService(SpacePermissionService permissionService, SpaceFileMapper fileMapper) {
        this.permissionService = permissionService;
        this.fileMapper = fileMapper;
    }

    public SpaceMember requireRead(Long spaceId, Long userId) {
        return permissionService.requireMember(spaceId, userId);
    }

    public SpaceMember requireCreate(Long spaceId, Long userId) {
        SpaceMember member = requireRead(spaceId, userId);
        if (SpaceConstant.ROLE_VIEWER.equals(member.getRole())) {
            throw new ForbiddenException("只读成员不能创建或导入文件");
        }
        return member;
    }

    public SpaceFile requireNodeAction(Long spaceId, Long fileId, Long userId, SpaceFileAction action) {
        SpaceMember member = requireRead(spaceId, userId);
        SpaceFile node = fileMapper.getById(spaceId, fileId);
        if (node == null || !"ACTIVE".equals(node.getLifecycleState())) {
            throw new NotFoundException("空间文件不存在");
        }
        if(action == SpaceFileAction.READ) return node;
        if (isManager(member)) {
            return node;
        }
        if (SpaceConstant.ROLE_VIEWER.equals(member.getRole())) {
            throw new ForbiddenException("只读成员不能修改文件");
        }
        if (!userId.equals(node.getCreatedBy())) {
            throw new ForbiddenException("成员只能修改自己提交的节点");
        }
        if (node.getDir() == 1 && (action == SpaceFileAction.RENAME
                || action == SpaceFileAction.MOVE || action == SpaceFileAction.DELETE)
                && fileMapper.countForeignOwnedInSubtree(spaceId, fileId, userId) > 0) {
            throw new ForbiddenException("目录包含其他成员创建的节点，不能操作整棵子树");
        }
        return node;
    }

    public SpaceFileCapabilityVO capabilities(SpaceFile node, Long userId) {
        SpaceMember member = requireRead(node.getSpaceId(), userId);
        boolean manager = isManager(member);
        boolean viewer = SpaceConstant.ROLE_VIEWER.equals(member.getRole());
        boolean own = userId.equals(node.getCreatedBy());
        boolean ownsSubtree = own && (node.getDir() != 1
                || fileMapper.countForeignOwnedInSubtree(node.getSpaceId(), node.getId(), userId) == 0);
        boolean mutable = manager || (!viewer && own);
        boolean subtreeMutable = manager || (!viewer && ownsSubtree);
        boolean root = Long.valueOf(0L).equals(node.getParentId());
        return SpaceFileCapabilityVO.builder()
                .canRead(true)
                .canCreate(!viewer && node.getDir() == 1)
                .canRename(!root && (node.getDir() == 1 ? subtreeMutable : mutable))
                .canMove(!root && (node.getDir() == 1 ? subtreeMutable : mutable))
                .canRemove(!root && (node.getDir() == 1 ? subtreeMutable : mutable))
                .canUploadVersion(node.getDir() == 0 && mutable)
                .canManageVersions(node.getDir() == 0 && mutable)
                .build();
    }

    private boolean isManager(SpaceMember member) {
        return SpaceConstant.ROLE_OWNER.equals(member.getRole())
                || SpaceConstant.ROLE_ADMIN.equals(member.getRole());
    }
}
