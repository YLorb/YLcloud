package com.ylcloud.service;

import com.ylcloud.DTO.UserLoginDTO;
import com.ylcloud.mapper.LoginMapper;
import com.ylcloud.entity.User;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

@Service
public class LoginService {

    private LoginMapper loginMapper;

    public User login(UserLoginDTO userLoginDTO) {
        User user = new User();
        user = loginMapper.getByUsername(userLoginDTO.getUsername());
        if(user == null) {
            throw new RuntimeException("用户不存在！");
        }
        else if(!user.getPassword().equals(userLoginDTO.getPassword())) {
            throw new RuntimeException("密码错误！");
        }
        return user;
    }
}
