package com.ylcloud.service;

import com.ylcloud.DTO.AdminResourceGrantCreateDTO;
import com.ylcloud.Exception.ForbiddenException;
import com.ylcloud.context.BaseContext;
import com.ylcloud.entity.Space;
import com.ylcloud.entity.SpaceMember;
import com.ylcloud.entity.User;
import com.ylcloud.mapper.AccessControlMapper;
import com.ylcloud.mapper.AdminResourceGrantMapper;
import com.ylcloud.mapper.LoginMapper;
import com.ylcloud.mapper.SpaceMapper;
import com.ylcloud.mapper.SpaceMemberMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminResourceGrantServiceTest {
    private final AdminResourceGrantMapper grantMapper = mock(AdminResourceGrantMapper.class);
    private final LoginMapper loginMapper = mock(LoginMapper.class);
    private final SpaceMapper spaceMapper = mock(SpaceMapper.class);
    private final SpaceMemberMapper memberMapper = mock(SpaceMemberMapper.class);
    private final AccessControlMapper auditMapper = mock(AccessControlMapper.class);
    private final AdminResourceGrantService service = new AdminResourceGrantService(
            grantMapper,loginMapper,spaceMapper,memberMapper,auditMapper
    );

    @AfterEach
    void clearContext() {
        BaseContext.removeCurrentId();
    }

    @Test
    void userCanGrantReadAndDownloadForOwnPrivateData() {
        BaseContext.setCurrentId(20L);
        when(loginMapper.getById(9L)).thenReturn(admin(9L));
        when(grantMapper.listScope(20L,9L,"USER_PRIVATE",20L)).thenReturn(List.of());

        service.grant(dto(9L,"USER_PRIVATE",20L,Set.of("READ","DOWNLOAD")));

        verify(grantMapper).activate(20L,9L,"USER_PRIVATE",20L,"READ");
        verify(grantMapper).activate(20L,9L,"USER_PRIVATE",20L,"DOWNLOAD");
    }

    @Test
    void userCannotGrantAnotherUsersPrivateData() {
        BaseContext.setCurrentId(20L);
        when(loginMapper.getById(9L)).thenReturn(admin(9L));

        assertThrows(ForbiddenException.class,() ->
                service.grant(dto(9L,"USER_PRIVATE",21L,Set.of("READ"))));
        verify(grantMapper,never()).activate(20L,9L,"USER_PRIVATE",21L,"READ");
    }

    @Test
    void onlyActualTeamOwnerCanCreateSpaceGrant() {
        BaseContext.setCurrentId(20L);
        when(loginMapper.getById(9L)).thenReturn(admin(9L));
        Space space = new Space();
        space.setId(44L);
        when(spaceMapper.getById(44L)).thenReturn(space);
        SpaceMember member = new SpaceMember();
        member.setRole("ADMIN");
        when(memberMapper.getActive(44L,20L)).thenReturn(member);

        assertThrows(ForbiddenException.class,() ->
                service.grant(dto(9L,"SPACE",44L,Set.of("READ"))));
    }

    private AdminResourceGrantCreateDTO dto(Long adminId, String type, Long resourceId, Set<String> actions) {
        AdminResourceGrantCreateDTO dto = new AdminResourceGrantCreateDTO();
        dto.setAdminUserId(adminId);
        dto.setResourceType(type);
        dto.setResourceId(resourceId);
        dto.setActions(actions);
        return dto;
    }

    private User admin(Long id) {
        User user = new User();
        user.setId(id);
        user.setRole("ADMIN");
        user.setStatus(1);
        user.setDeploymentOwner(false);
        return user;
    }
}
