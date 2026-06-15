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
    private final SignMapper signMapper;
    private final FileService fileService;
    private final SpaceService spaceService;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public SignService(SignMapper signMapper, FileService fileService, SpaceService spaceService) {
        this.signMapper = signMapper;
        this.fileService = fileService;
        this.spaceService = spaceService;
    }

    @Transactional
    public void signup(UserRegisterDTO userRegisterDTO) {
        if(signMapper.countByUsername(userRegisterDTO.getUsername()) > 0) {
            throw new BaseException("用户名已存在");
        }

        User user = new User();
        BeanUtils.copyProperties(userRegisterDTO,user);
        user.setPassword(passwordEncoder.encode(userRegisterDTO.getPassword()));
        user.setStatus(StatusConstant.ENABLE);
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        int rows = signMapper.insert(user);
        if(rows == 0 || user.getId() == null) {
            throw new RuntimeException("用户注册失败");
        }
        user.setRootID(fileService.getRootId(user.getId()));
        rows = signMapper.updateAll(user.getRootID(),user.getId());
        if(rows == 0) {
            throw new RuntimeException("用户根目录绑定失败");
        }
        spaceService.createDefaultPersonalSpace(user.getId(),user.getUsername());
    }
}
