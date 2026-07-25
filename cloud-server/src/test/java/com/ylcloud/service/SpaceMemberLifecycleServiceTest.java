package com.ylcloud.service;

import com.ylcloud.DTO.SpaceMemberAddDTO;
import com.ylcloud.Exception.ForbiddenException;
import com.ylcloud.constant.SpaceConstant;
import com.ylcloud.entity.Space;
import com.ylcloud.mapper.SpaceMapper;
import com.ylcloud.mapper.SpaceMemberMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpaceMemberLifecycleServiceTest {
    private final SpaceMemberMapper memberMapper = mock(SpaceMemberMapper.class);
    private final SpacePermissionService permissionService = mock(SpacePermissionService.class);
    private final SpaceMapper spaceMapper = mock(SpaceMapper.class);
    private final SpaceMemberService service =
            new SpaceMemberService(memberMapper,permissionService,spaceMapper);

    @Test
    void personalSpaceRejectsMemberInvitationBeforePermissionPath() {
        Space personal = new Space();
        personal.setId(9L);
        personal.setType(SpaceConstant.TYPE_PERSONAL);
        personal.setLifecycleState(SpaceConstant.LIFECYCLE_ACTIVE);
        when(spaceMapper.getById(9L)).thenReturn(personal);
        SpaceMemberAddDTO dto = new SpaceMemberAddDTO();
        dto.setUserId(2L);

        assertThrows(ForbiddenException.class,() -> service.addMember(9L,dto,1L));
        verify(permissionService,never()).requireAdmin(9L,1L);
        verify(memberMapper,never()).activate(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }
}
