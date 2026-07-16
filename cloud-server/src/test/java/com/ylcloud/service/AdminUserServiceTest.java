package com.ylcloud.service;

import com.ylcloud.DTO.AdminUserUpdateDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.context.BaseContext;
import com.ylcloud.entity.User;
import com.ylcloud.mapper.AdminUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminUserServiceTest {
    private final AdminUserMapper mapper = mock(AdminUserMapper.class);
    private final AccessControlService accessControlService = mock(AccessControlService.class);
    private final SignService signService = mock(SignService.class);
    private final AdminUserService service = new AdminUserService(mapper,accessControlService,signService);

    @AfterEach
    void clearContext() { BaseContext.removeCurrentId(); }

    @Test
    void preventsAdministratorFromChangingCurrentAccount() {
        BaseContext.setCurrentId(42L);
        when(mapper.lockById(42L)).thenReturn(user(42L,"ADMIN",1));

        assertThrows(BaseException.class,() -> service.update(42L,update("USER",null)));
        verify(mapper,never()).updateRole(42L,"USER");
    }

    @Test
    void preventsRemovingLastActiveAdministrator() {
        BaseContext.setCurrentId(42L);
        when(mapper.lockById(16L)).thenReturn(user(16L,"ADMIN",1));
        when(mapper.countActiveAdmins()).thenReturn(1);

        assertThrows(BaseException.class,() -> service.update(16L,update("USER",null)));
    }

    private User user(Long id, String role, int status) { User user = new User(); user.setId(id); user.setRole(role); user.setStatus(status); user.setUsername("user" + id); return user; }
    private AdminUserUpdateDTO update(String role, Integer status) { AdminUserUpdateDTO dto = new AdminUserUpdateDTO(); dto.setRole(role); dto.setStatus(status); return dto; }
}
