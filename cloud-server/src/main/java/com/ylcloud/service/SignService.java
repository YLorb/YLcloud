package com.ylcloud.service;

import com.ylcloud.DTO.UserRegisterDTO;
import com.ylcloud.context.BaseContext;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.SignMapper;
import com.ylcloud.entity.User;
import com.ylcloud.constant.StatusConstant;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class SignService {

    private final SignMapper signMapper;

    @Autowired
    private FileService fileService;

    public SignService(SignMapper signMapper) {
        this.signMapper = signMapper;
    }

    public void signup(UserRegisterDTO userRegisterDTO) {
        User user = new User();
        BeanUtils.copyProperties(userRegisterDTO,user);
        user.setStatus(StatusConstant.ENABLE);
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        signMapper.insert(user);
        user.setRootID(fileService.getRootId(user.getId()));
        signMapper.updateAll(user.getRootID(),user.getId());
    }
}
