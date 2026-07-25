package com.ylcloud.service;

import com.ylcloud.DTO.UserRegisterDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.constant.StatusConstant;
import com.ylcloud.entity.User;
import com.ylcloud.mapper.SignMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class SignService {
    private static final String ROLE_USER = "USER";
    private static final String ROLE_ADMIN = "ADMIN";

    private final SignMapper signMapper;
    private final FileService fileService;
    private final SpaceService spaceService;
    private final SiteSettingService siteSettingService;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public SignService(SignMapper signMapper, FileService fileService, SpaceService spaceService, SiteSettingService siteSettingService) {
        this.signMapper = signMapper;
        this.fileService = fileService;
        this.spaceService = spaceService;
        this.siteSettingService = siteSettingService;
    }

    @Transactional
    public void signup(UserRegisterDTO userRegisterDTO) {
        // The guard must be the first database read in this transaction. Under
        // MySQL REPEATABLE READ, reading site settings first would establish a
        // stale snapshot while another first-user transaction is still running.
        signMapper.lockRegistrationGuard();
        if(!Boolean.TRUE.equals(siteSettingService.getBoolean(SiteSettingService.SITE_ALLOW_REGISTER,true))) {
            throw new BaseException("当前站点未开放注册");
        }
        if(signMapper.countByUsername(userRegisterDTO.getUsername()) > 0) {
            throw new BaseException("用户名已存在");
        }

        boolean deploymentOwner = signMapper.countAll() == 0;
        createUser(userRegisterDTO,deploymentOwner ? ROLE_ADMIN : ROLE_USER,null,deploymentOwner);
    }

    @Transactional
    public Long createByAdmin(UserRegisterDTO userRegisterDTO, String role, String email) {
        signMapper.lockRegistrationGuard();
        if(signMapper.countByUsername(userRegisterDTO.getUsername()) > 0) {
            throw new BaseException("用户名已存在");
        }
        if(!ROLE_ADMIN.equals(role) && !ROLE_USER.equals(role)) {
            throw new BaseException("用户角色仅支持 ADMIN 或 USER");
        }
        return createUser(userRegisterDTO,role,email,false);
    }

    private Long createUser(UserRegisterDTO userRegisterDTO, String role, String email, boolean deploymentOwner) {
        User user = new User();
        BeanUtils.copyProperties(userRegisterDTO,user);
        user.setPassword(passwordEncoder.encode(userRegisterDTO.getPassword()));
        user.setStatus(StatusConstant.ENABLE);
        user.setRole(role);
        user.setDeploymentOwner(deploymentOwner);
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        int rows = signMapper.insert(user);
        if(rows == 0 || user.getId() == null) {
            throw new RuntimeException("用户注册失败");
        }
        user.setRootID(fileService.getRootId(user.getId()));
        rows = signMapper.updateAll(user.getRootID(),user.getId(),email == null || email.isBlank() ? null : email.trim());
        if(rows == 0) {
            throw new RuntimeException("用户根目录绑定失败");
        }
        spaceService.createDefaultPersonalSpace(user.getId(),user.getUsername());
        return user.getId();
    }
}
