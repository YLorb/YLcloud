package com.ylcloud.service;

import com.ylcloud.DTO.UserRegisterDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.constant.StatusConstant;
import com.ylcloud.entity.User;
import com.ylcloud.mapper.SignMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 用户注册业务服务。
 */
@Service
public class SignService {

    private final SignMapper signMapper;
    private final FileService fileService;
    private final SpaceService spaceService;

    /**
     * 创建注册业务服务。
     *
     * @param signMapper 注册数据访问对象
     * @param fileService 文件业务服务
     * @param spaceService 空间业务服务
     */
    public SignService(SignMapper signMapper, FileService fileService, SpaceService spaceService) {
        this.signMapper = signMapper;
        this.fileService = fileService;
        this.spaceService = spaceService;
    }

    /**
     * 注册用户，并初始化用户根目录和默认个人空间。
     *
     * @param userRegisterDTO 注册参数
     */
    @Transactional
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
        spaceService.createDefaultPersonalSpace(user.getId(),user.getUsername());
    }
}
