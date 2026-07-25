package com.ylcloud.service;

import com.ylcloud.Exception.ForbiddenException;
import com.ylcloud.authorization.AccessSubject;
import com.ylcloud.authorization.AuthorizationBasis;
import com.ylcloud.authorization.ResourceAction;
import com.ylcloud.authorization.ResourceType;
import com.ylcloud.entity.SpaceMember;
import com.ylcloud.entity.User;
import com.ylcloud.mapper.AccessControlMapper;
import com.ylcloud.mapper.AdminResourceGrantMapper;
import com.ylcloud.mapper.LoginMapper;
import com.ylcloud.mapper.SpaceMemberMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthorizationServiceTest {
    private final LoginMapper loginMapper = mock(LoginMapper.class);
    private final SpaceMemberMapper spaceMemberMapper = mock(SpaceMemberMapper.class);
    private final AdminResourceGrantMapper grantMapper = mock(AdminResourceGrantMapper.class);
    private final AccessControlMapper accessControlMapper = mock(AccessControlMapper.class);
    private final AuthorizationService service = new AuthorizationService(
            loginMapper,spaceMemberMapper,grantMapper,accessControlMapper
    );

    @Test
    void deniesOrdinaryAdministratorWithoutExplicitPrivateGrant() {
        when(loginMapper.getById(9L)).thenReturn(user(9L,"ADMIN",false));

        assertThrows(ForbiddenException.class,() -> service.require(
                AccessSubject.user(9L),ResourceType.USER_PRIVATE,20L,ResourceAction.READ
        ));
    }

    @Test
    void revocationIsVisibleOnTheNextAuthorizationCheck() {
        when(loginMapper.getById(9L)).thenReturn(user(9L,"ADMIN",false));
        when(grantMapper.countActive(9L,"USER_PRIVATE",20L,"READ")).thenReturn(1,0);

        assertEquals(AuthorizationBasis.ADMIN_RESOURCE_GRANT,service.require(
                AccessSubject.user(9L),ResourceType.USER_PRIVATE,20L,ResourceAction.READ
        ));
        assertThrows(ForbiddenException.class,() -> service.require(
                AccessSubject.user(9L),ResourceType.USER_PRIVATE,20L,ResourceAction.READ
        ));
    }

    @Test
    void deploymentOwnerBypassesMembershipAndWritesAuditHook() {
        when(loginMapper.getById(1L)).thenReturn(user(1L,"ADMIN",true));

        assertEquals(AuthorizationBasis.DEPLOYMENT_OWNER,service.require(
                AccessSubject.systemTask(1L,"task-1"),ResourceType.SPACE,44L,ResourceAction.MANAGE
        ));
        verify(accessControlMapper).insertAudit(
                1L,"SPACE",44L,"DEPLOYMENT_OWNER_MANAGE_SYSTEM_TASK",null,null
        );
    }

    @Test
    void spaceMemberAccessDoesNotDependOnGlobalRole() {
        when(loginMapper.getById(7L)).thenReturn(user(7L,"USER",false));
        SpaceMember member = new SpaceMember();
        member.setUserId(7L);
        member.setSpaceId(44L);
        member.setRole("MEMBER");
        when(spaceMemberMapper.getActive(44L,7L)).thenReturn(member);

        assertEquals(AuthorizationBasis.SPACE_MEMBER,service.require(
                AccessSubject.apiKey(7L,88L),ResourceType.SPACE,44L,ResourceAction.READ
        ));
    }

    private User user(Long id, String role, boolean owner) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        user.setStatus(1);
        user.setDeploymentOwner(owner);
        return user;
    }
}
