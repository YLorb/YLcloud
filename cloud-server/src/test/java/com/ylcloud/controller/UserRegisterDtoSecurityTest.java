package com.ylcloud.controller;

import com.ylcloud.DTO.UserRegisterDTO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserRegisterDtoSecurityTest {
    @Test
    void toStringExcludesPassword() {
        UserRegisterDTO dto = new UserRegisterDTO();
        dto.setUsername("security-user");
        dto.setPassword("never-log-this-secret");
        dto.setNickname("安全测试");

        assertTrue(dto.toString().contains("security-user"));
        assertFalse(dto.toString().contains("never-log-this-secret"));
        assertFalse(dto.toString().contains("password"));
    }
}
