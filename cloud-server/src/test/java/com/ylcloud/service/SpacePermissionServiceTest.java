package com.ylcloud.service;

import com.ylcloud.authorization.AccessSubject;
import com.ylcloud.authorization.AuthorizationBasis;
import com.ylcloud.authorization.ResourceAction;
import com.ylcloud.authorization.ResourceType;
import com.ylcloud.entity.SpaceMember;
import com.ylcloud.mapper.SpaceMemberMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpacePermissionServiceTest {
    private final SpaceMemberMapper mapper = mock(SpaceMemberMapper.class);
    private final AuthorizationService authorizationService = mock(AuthorizationService.class);
    private final SpacePermissionService service = new SpacePermissionService(mapper,authorizationService);

    @Test
    void explicitGrantAllowsReadWithoutCreatingFakeDatabaseMembership() {
        when(authorizationService.require(
                AccessSubject.user(9L),ResourceType.SPACE,44L,ResourceAction.READ
        )).thenReturn(AuthorizationBasis.ADMIN_RESOURCE_GRANT);

        SpaceMember member = service.requireMember(44L,9L);

        assertEquals("MEMBER",member.getRole());
        verify(authorizationService).require(
                AccessSubject.user(9L),ResourceType.SPACE,44L,ResourceAction.READ
        );
    }
}
