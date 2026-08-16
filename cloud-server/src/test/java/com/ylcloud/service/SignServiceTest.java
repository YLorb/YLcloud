package com.ylcloud.service;

import com.ylcloud.DTO.UserRegisterDTO;
import com.ylcloud.entity.User;
import com.ylcloud.mapper.SignMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SignServiceTest {
    private SignMapper signMapper;
    private FileService fileService;
    private SpaceService spaceService;
    private SiteSettingService siteSettingService;
    private SignService signService;

    @BeforeEach
    void setUp() {
        signMapper = mock(SignMapper.class);
        fileService = mock(FileService.class);
        spaceService = mock(SpaceService.class);
        siteSettingService = mock(SiteSettingService.class);
        signService = new SignService(signMapper,fileService,spaceService,siteSettingService);

        when(siteSettingService.getBoolean(SiteSettingService.SITE_ALLOW_REGISTER,true)).thenReturn(true);
        when(signMapper.lockRegistrationGuard()).thenReturn(1);
        when(signMapper.countByUsername(any())).thenReturn(0);
        when(signMapper.insert(any())).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(100L);
            return 1;
        });
        when(fileService.getRootId(100L)).thenReturn(200L);
        when(signMapper.updateAll(200L,100L,null)).thenReturn(1);
    }

    @Test
    void assignsAdminRoleToFirstRegisteredUser() {
        when(signMapper.countAll()).thenReturn(0);

        signService.signup(registration("first-user"));

        assertInsertedRole("ADMIN");
        assertInsertedOwner(true);
        InOrder order = inOrder(signMapper,siteSettingService);
        order.verify(signMapper).lockRegistrationGuard();
        order.verify(siteSettingService).getBoolean(SiteSettingService.SITE_ALLOW_REGISTER,true);
        order.verify(signMapper).countByUsername("first-user");
        order.verify(signMapper).countAll();
        order.verify(signMapper).insert(any());
    }

    @Test
    void assignsUserRoleWhenAnotherUserAlreadyExists() {
        when(signMapper.countAll()).thenReturn(1);

        signService.signup(registration("later-user"));

        assertInsertedRole("USER");
        assertInsertedOwner(false);
    }

    private UserRegisterDTO registration(String username) {
        UserRegisterDTO dto = new UserRegisterDTO();
        dto.setUsername(username);
        dto.setPassword("a-secure-password");
        dto.setNickname(username);
        return dto;
    }

    private void assertInsertedRole(String expectedRole) {
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(signMapper).insert(userCaptor.capture());
        assertEquals(expectedRole,userCaptor.getValue().getRole());
    }

    private void assertInsertedOwner(boolean expectedOwner) {
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(signMapper).insert(userCaptor.capture());
        if(expectedOwner) {
            assertTrue(userCaptor.getValue().getDeploymentOwner());
        } else {
            assertFalse(userCaptor.getValue().getDeploymentOwner());
        }
    }
}
