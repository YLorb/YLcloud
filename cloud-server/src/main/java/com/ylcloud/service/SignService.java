package com.ylcloud.service;

import com.ylcloud.DTO.UserFileDTO;
import com.ylcloud.DTO.UserRegisterDTO;
import com.ylcloud.context.BaseContext;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.SignMapper;
import com.ylcloud.entity.User;
import com.ylcloud.constant.StatusConstant;
import com.ylcloud.utils.UuidUtil;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class SignService {

    private final SignMapper signMapper;

    @Autowired
    private FileInfoMapper fileInfoMapper;

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
        /*
        下面这一步，已经在调用的时候完成了
         */
        /*UserFileDTO userFileDTO = UserFileDTO.builder()
                .userId(user.getId())
                .Dir(1)
                .path("/")
                .fileName("/")
                .parentId(user.getId())
                .status(1)
                .fileUuid(UuidUtil.randomUuid())
                .createtime(LocalDateTime.now())
                .updatetime(LocalDateTime.now()).build();
        fileInfoMapper.insertFile_User(userFileDTO);*/
        signMapper.updateAll(user.getRootID(),user.getId());
    }
}
