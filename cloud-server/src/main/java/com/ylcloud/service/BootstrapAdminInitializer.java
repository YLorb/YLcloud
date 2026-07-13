package com.ylcloud.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class BootstrapAdminInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);

    private final SignService signService;
    private final boolean enabled;
    private final String username;
    private final String password;
    private final String nickname;

    public BootstrapAdminInitializer(
            SignService signService,
            @Value("${ylcloud.bootstrap-admin.enabled:false}") boolean enabled,
            @Value("${ylcloud.bootstrap-admin.username:}") String username,
            @Value("${ylcloud.bootstrap-admin.password:}") String password,
            @Value("${ylcloud.bootstrap-admin.nickname:YLcloud Administrator}") String nickname ) {
        this.signService = signService;
        this.enabled = enabled;
        this.username = username;
        this.password = password;
        this.nickname = nickname;
    }

    @Override
    public void run(ApplicationArguments args) {
        if(!enabled) {
            log.info("Bootstrap administrator initialization skipped");
            return;
        }
        requireText(username,"YLCLOUD_BOOTSTRAP_ADMIN_USERNAME");
        requireText(password,"YLCLOUD_BOOTSTRAP_ADMIN_PASSWORD");
        requireText(nickname,"YLCLOUD_BOOTSTRAP_ADMIN_NICKNAME");
        if(password.length() < 12) {
            throw new IllegalStateException("YLCLOUD_BOOTSTRAP_ADMIN_PASSWORD must contain at least 12 characters");
        }
        if(signService.bootstrapAdmin(username,password,nickname)) {
            log.info("Created bootstrap administrator: {}",username);
        } else {
            log.info("Bootstrap administrator not created because users already exist");
        }
    }

    private void requireText(String value, String variableName) {
        if(value == null || value.isBlank()) {
            throw new IllegalStateException(variableName + " is required when bootstrap administrator initialization is enabled");
        }
    }
}
