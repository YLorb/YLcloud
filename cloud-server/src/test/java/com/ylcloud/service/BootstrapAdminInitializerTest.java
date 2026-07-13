package com.ylcloud.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BootstrapAdminInitializerTest {
    private static final DefaultApplicationArguments NO_ARGS = new DefaultApplicationArguments(new String[0]);

    @Test
    void skipsWhenDisabled() {
        SignService signService = mock(SignService.class);
        BootstrapAdminInitializer initializer = new BootstrapAdminInitializer(
                signService,false,"","",""
        );

        initializer.run(NO_ARGS);

        verify(signService,never()).bootstrapAdmin(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }

    @Test
    void createsConfiguredAdministrator() {
        SignService signService = mock(SignService.class);
        when(signService.bootstrapAdmin("admin","a-secure-password","Administrator")).thenReturn(true);
        BootstrapAdminInitializer initializer = new BootstrapAdminInitializer(
                signService,true,"admin","a-secure-password","Administrator"
        );

        initializer.run(NO_ARGS);

        verify(signService).bootstrapAdmin("admin","a-secure-password","Administrator");
    }

    @Test
    void rejectsWeakBootstrapPassword() {
        SignService signService = mock(SignService.class);
        BootstrapAdminInitializer initializer = new BootstrapAdminInitializer(
                signService,true,"admin","short","Administrator"
        );

        assertThrows(IllegalStateException.class,() -> initializer.run(NO_ARGS));
        verify(signService,never()).bootstrapAdmin(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }
}
