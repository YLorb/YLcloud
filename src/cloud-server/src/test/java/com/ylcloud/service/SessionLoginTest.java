package com.ylcloud.service;

import com.ylcloud.DTO.UserLoginDTO;
import com.ylcloud.Exception.UnauthorizedException;
import com.ylcloud.entity.User;
import com.ylcloud.mapper.LoginMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SessionLoginTest {
    private User user() {
        User u=new User(); u.setId(1L); u.setUsername("alice"); u.setStatus(1); u.setAccountStatus("ACTIVE");
        u.setPassword(new BCryptPasswordEncoder().encode("good-password")); return u;
    }
    private UserLoginDTO dto(String password) {
        var dto=new UserLoginDTO();dto.setUsername("alice");dto.setPassword(password);return dto;
    }
    @Test void validPasswordCreatesSessionWithoutExposingCredentialInUserDto() {
        var users=mock(LoginMapper.class);var sessions=mock(BrowserSessionService.class);
        when(users.getByUsername("alice")).thenReturn(user());
        when(sessions.create(eq(1L),any())).thenReturn("opaque");
        var service=new LoginService(users,sessions,mock(SecurityAuditService.class));
        var result=service.login(dto("good-password"),new MockHttpServletRequest());
        assertEquals(1L,result.user().getId());assertEquals("opaque",result.credential());
        assertFalse(result.user().toString().contains("opaque"));
        verify(users).lockUserId(1L);
    }
    @Test void wrongPasswordAndDisabledAccountCannotCreateSession() {
        var users=mock(LoginMapper.class);var sessions=mock(BrowserSessionService.class);
        User user=user();when(users.getByUsername("alice")).thenReturn(user);
        var service=new LoginService(users,sessions,mock(SecurityAuditService.class));
        assertThrows(UnauthorizedException.class,()->service.login(dto("wrong"),new MockHttpServletRequest()));
        user.setStatus(0);
        assertThrows(UnauthorizedException.class,()->service.login(dto("good-password"),new MockHttpServletRequest()));
        verify(sessions,never()).create(any(),any());
    }
    @Test void passwordChangeRevokesEverySession() {
        var users=mock(LoginMapper.class);var sessions=mock(BrowserSessionService.class);
        when(users.getById(1L)).thenReturn(user());
        new LoginService(users,sessions,mock(SecurityAuditService.class)).changePassword(1L,"good-password","new-password");
        verify(users).updatePassword(eq(1L),argThat(hash->new BCryptPasswordEncoder().matches("new-password",hash)));
        verify(sessions).revokeAll(1L);
    }
}
