package com.ylcloud.service;

import com.ylcloud.Exception.ForbiddenException;
import com.ylcloud.VO.SpaceFileCapabilityVO;
import com.ylcloud.authorization.SpaceFileAction;
import com.ylcloud.constant.SpaceConstant;
import com.ylcloud.entity.SpaceFile;
import com.ylcloud.entity.SpaceMember;
import com.ylcloud.mapper.SpaceFileMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpaceFileAccessServiceTest {
    private final SpacePermissionService permissions=mock(SpacePermissionService.class);
    private final SpaceFileMapper files=mock(SpaceFileMapper.class);
    private final SpaceFileAccessService service=new SpaceFileAccessService(permissions,files);

    @Test
    void memberCannotControlDirectoryWhenAnyDescendantBelongsToAnotherMember() {
        SpaceMember member=member(7L,SpaceConstant.ROLE_MEMBER);
        SpaceFile folder=node(11L,7L,true);
        when(permissions.requireMember(3L,7L)).thenReturn(member);
        when(files.getById(3L,11L)).thenReturn(folder);
        when(files.countForeignOwnedInSubtree(3L,11L,7L)).thenReturn(1);

        ForbiddenException error=assertThrows(ForbiddenException.class,
                () -> service.requireNodeAction(3L,11L,7L,SpaceFileAction.DELETE));

        assertTrue(error.getMessage().contains("其他成员"));
    }

    @Test
    void memberMayControlEntireDirectoryOnlyWhenEveryNodeIsOwnedByThem() {
        SpaceMember member=member(7L,SpaceConstant.ROLE_MEMBER);
        SpaceFile folder=node(11L,7L,true);
        when(permissions.requireMember(3L,7L)).thenReturn(member);
        when(files.getById(3L,11L)).thenReturn(folder);
        when(files.countForeignOwnedInSubtree(3L,11L,7L)).thenReturn(0);

        assertSame(folder,service.requireNodeAction(3L,11L,7L,SpaceFileAction.MOVE));
        SpaceFileCapabilityVO capability=service.capabilities(folder,7L);
        assertTrue(capability.isCanMove());
        assertTrue(capability.isCanRemove());
    }

    @Test
    void viewerCanReadButCannotCreateOrMutate() {
        SpaceMember viewer=member(8L,SpaceConstant.ROLE_VIEWER);
        SpaceFile file=node(12L,8L,false);
        when(permissions.requireMember(3L,8L)).thenReturn(viewer);
        when(files.getById(3L,12L)).thenReturn(file);

        assertThrows(ForbiddenException.class,() -> service.requireCreate(3L,8L));
        assertThrows(ForbiddenException.class,
                () -> service.requireNodeAction(3L,12L,8L,SpaceFileAction.RENAME));
        assertFalse(service.capabilities(file,8L).isCanRename());
    }

    private SpaceMember member(Long userId,String role) { SpaceMember member=new SpaceMember(); member.setUserId(userId); member.setRole(role); return member; }
    private SpaceFile node(Long id,Long creator,boolean directory) { SpaceFile node=new SpaceFile(); node.setId(id); node.setSpaceId(3L); node.setCreatedBy(creator); node.setDir(directory?1:0); node.setParentId(1L); node.setLifecycleState("ACTIVE"); return node; }
}
