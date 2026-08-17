package com.ylcloud.service;

import com.ylcloud.DTO.SpaceLeaveDTO;
import com.ylcloud.DTO.SpaceOwnerTransferDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.Exception.ConflictException;
import com.ylcloud.Exception.ForbiddenException;
import com.ylcloud.config.RagProperties;
import com.ylcloud.constant.SpaceConstant;
import com.ylcloud.entity.Space;
import com.ylcloud.entity.SpaceFile;
import com.ylcloud.entity.SpaceMember;
import com.ylcloud.mapper.SpaceDissolutionOutboxMapper;
import com.ylcloud.mapper.SpaceFileMapper;
import com.ylcloud.mapper.SpaceMapper;
import com.ylcloud.mapper.SpaceMemberMapper;
import com.ylcloud.mapper.SpaceRagMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpaceLifecycleServiceTest {
    private final SpaceMapper spaceMapper = mock(SpaceMapper.class);
    private final SpaceMemberMapper memberMapper = mock(SpaceMemberMapper.class);
    private final SpaceFileMapper spaceFileMapper = mock(SpaceFileMapper.class);
    private final SpaceRagMapper spaceRagMapper = mock(SpaceRagMapper.class);
    private final SpaceDissolutionOutboxMapper outboxMapper = mock(SpaceDissolutionOutboxMapper.class);
    private final SpacePermissionService permissionService = mock(SpacePermissionService.class);
    private final QuotaService quotaService = mock(QuotaService.class);
    private final SpaceService service = new SpaceService(
            spaceMapper,
            memberMapper,
            spaceFileMapper,
            spaceRagMapper,
            permissionService,
            new RagProperties(),
            mock(InitialFileVersionService.class),
            outboxMapper
    );

    private Space team;

    @BeforeEach
    void setUp() {
        service.setQuotaService(quotaService);
        team = new Space();
        team.setId(10L);
        team.setName("研发团队");
        team.setType(SpaceConstant.TYPE_TEAM);
        team.setOwnerId(1L);
        team.setLifecycleState(SpaceConstant.LIFECYCLE_ACTIVE);
    }

    @Test
    void defaultPersonalSpaceRegistersQuotaAccountForSpaceFiles() {
        doAnswer(invocation -> {
            ((Space) invocation.getArgument(0)).setId(12L);
            return 1;
        }).when(spaceMapper).insert(any(Space.class));
        doAnswer(invocation -> {
            ((SpaceFile) invocation.getArgument(0)).setId(13L);
            return 1;
        }).when(spaceFileMapper).insert(any(SpaceFile.class));

        service.createDefaultPersonalSpace(7L,"test-user");

        verify(quotaService).registerTeam(12L,7L);
    }

    @Test
    void transfersOwnerAtomicallyAndKeepsExactlyOneOwner() {
        when(spaceMapper.getByIdForUpdate(10L)).thenReturn(team);
        when(memberMapper.getActiveForUpdate(10L,2L)).thenReturn(member(2L,SpaceConstant.ROLE_MEMBER));
        when(memberMapper.updateRole(any(),any(),anyString(),any())).thenReturn(1);
        when(spaceMapper.updateOwner(any(),any(),any())).thenReturn(1);
        when(memberMapper.countActiveOwners(10L)).thenReturn(1);
        SpaceOwnerTransferDTO dto = new SpaceOwnerTransferDTO();
        dto.setTargetUserId(2L);

        assertTrue(service.transferOwner(10L,dto,1L));
        verify(memberMapper).updateRole(eq(10L),eq(1L),eq(SpaceConstant.ROLE_MEMBER),any());
        verify(memberMapper).updateRole(eq(10L),eq(2L),eq(SpaceConstant.ROLE_OWNER),any());
        verify(spaceMapper).updateOwner(eq(10L),eq(2L),any());
    }

    @Test
    void concurrentOrStaleTransferCannotActAsFormerOwner() {
        team.setOwnerId(2L);
        when(spaceMapper.getByIdForUpdate(10L)).thenReturn(team);
        SpaceOwnerTransferDTO dto = new SpaceOwnerTransferDTO();
        dto.setTargetUserId(3L);

        assertThrows(ForbiddenException.class,() -> service.transferOwner(10L,dto,1L));
        verify(memberMapper,never()).updateRole(any(),any(),anyString(),any());
    }

    @Test
    void personalSpaceCannotTransferOrDelete() {
        team.setType(SpaceConstant.TYPE_PERSONAL);
        when(spaceMapper.getByIdForUpdate(10L)).thenReturn(team);
        when(spaceMapper.getById(10L)).thenReturn(team);
        SpaceOwnerTransferDTO dto = new SpaceOwnerTransferDTO();
        dto.setTargetUserId(2L);

        assertThrows(ForbiddenException.class,() -> service.transferOwner(10L,dto,1L));
        assertThrows(ForbiddenException.class,() -> service.deleteSpace(10L,1L));
    }

    @Test
    void ownerMustTransferBeforeLeavingWhenMembersRemain() {
        when(spaceMapper.getByIdForUpdate(10L)).thenReturn(team);
        when(memberMapper.getActiveForUpdate(10L,1L)).thenReturn(member(1L,SpaceConstant.ROLE_OWNER));
        when(memberMapper.countActive(10L)).thenReturn(2);

        assertThrows(ConflictException.class,() -> service.leaveSpace(10L,new SpaceLeaveDTO(),1L));
        verify(spaceMapper,never()).markDissolving(any(),any());
    }

    @Test
    void soleOwnerNeedsExactHighRiskConfirmation() {
        when(spaceMapper.getByIdForUpdate(10L)).thenReturn(team);
        when(memberMapper.getActiveForUpdate(10L,1L)).thenReturn(member(1L,SpaceConstant.ROLE_OWNER));
        when(memberMapper.countActive(10L)).thenReturn(1);
        SpaceLeaveDTO dto = new SpaceLeaveDTO();
        dto.setConfirmDissolve(true);
        dto.setConfirmationName("错误名称");

        assertThrows(ConflictException.class,() -> service.leaveSpace(10L,dto,1L));
        verify(outboxMapper,never()).insertRequested(anyString(),any(),any(),any());
    }

    @Test
    void soleOwnerConfirmationMovesToDissolvingAndWritesOutbox() {
        when(spaceMapper.getByIdForUpdate(10L)).thenReturn(team);
        when(memberMapper.getActiveForUpdate(10L,1L)).thenReturn(member(1L,SpaceConstant.ROLE_OWNER));
        when(memberMapper.countActive(10L)).thenReturn(1);
        when(spaceMapper.markDissolving(any(),any())).thenReturn(1);
        when(outboxMapper.insertRequested(anyString(),any(),any(),any())).thenReturn(1);
        SpaceLeaveDTO dto = new SpaceLeaveDTO();
        dto.setConfirmDissolve(true);
        dto.setConfirmationName("研发团队");

        assertTrue(service.leaveSpace(10L,dto,1L));
        verify(spaceMapper).markDissolving(eq(10L),any());
        verify(outboxMapper).insertRequested(anyString(),eq(10L),eq(1L),any());
    }

    @Test
    void ordinaryMemberCanLeaveWithoutDissolvingSpace() {
        when(spaceMapper.getByIdForUpdate(10L)).thenReturn(team);
        when(memberMapper.getActiveForUpdate(10L,2L)).thenReturn(member(2L,SpaceConstant.ROLE_MEMBER));
        when(memberMapper.disable(any(),any(),any())).thenReturn(1);

        assertTrue(service.leaveSpace(10L,null,2L));
        verify(memberMapper).disable(eq(10L),eq(2L),any());
        verify(spaceMapper,never()).markDissolving(any(),any());
    }

    @Test
    void legacyDeleteCannotBypassDissolutionConfirmation() {
        when(spaceMapper.getById(10L)).thenReturn(team);
        when(permissionService.requireOwner(10L,1L)).thenReturn(member(1L,SpaceConstant.ROLE_OWNER));

        assertThrows(ConflictException.class,() -> service.deleteSpace(10L,1L));
        verify(spaceMapper,never()).disable(any(),any());
    }

    private SpaceMember member(Long userId, String role) {
        SpaceMember member = new SpaceMember();
        member.setSpaceId(10L);
        member.setUserId(userId);
        member.setRole(role);
        member.setStatus(1);
        return member;
    }
}
