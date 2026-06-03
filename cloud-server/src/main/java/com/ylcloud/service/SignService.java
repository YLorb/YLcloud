package com.ylcloud.service;

import com.ylcloud.Exception.BaseException;
import com.ylcloud.DTO.UserRegisterDTO;
import com.ylcloud.mapper.SignMapper;
import com.ylcloud.entity.User;
import com.ylcloud.constant.StatusConstant;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class SignService {

    private final SignMapper signMapper;

    private final FileService fileService;

    /**
     * 创建注册业务服务。
     *
     * @param signMapper 注册数据访问对象
     * @param fileService 文件业务服务
     */
    public SignService(SignMapper signMapper, FileService fileService) {
        this.signMapper = signMapper;
        this.fileService = fileService;
    }

    /**
     * 注册用户并创建用户根目录关系。
     *
     * @param userRegisterDTO 注册参数
     */
    public void signup(UserRegisterDTO userRegisterDTO) {
        if (signMapper.countByUsername(userRegisterDTO.getUsername()) > 0) {
            throw new BaseException("用户名已存在");
        }

        User user = new User();
        BeanUtils.copyProperties(userRegisterDTO,user);
        user.setStatus(StatusConstant.ENABLE);
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        int rows = signMapper.insert(user);
        if (rows == 0 || user.getId() == null) {
            throw new RuntimeException("用户注册失败");
        }
        user.setRootID(fileService.getRootId(user.getId()));
        rows = signMapper.updateAll(user.getRootID(),user.getId());
        if (rows == 0) {
            throw new RuntimeException("用户根目录绑定失败");
        }
    }
}
