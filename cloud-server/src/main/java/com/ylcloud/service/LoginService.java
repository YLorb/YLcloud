package com.ylcloud.service;

import com.ylcloud.DTO.UserLoginDTO;
import com.ylcloud.VO.UserLoginVO;
import com.ylcloud.context.BaseContext;
import com.ylcloud.mapper.LoginMapper;
import com.ylcloud.entity.User;
import com.ylcloud.utils.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class LoginService {

    @Autowired
    private LoginMapper loginMapper;

    @Autowired
    private JwtUtil jwtutil;

    public UserLoginVO login(UserLoginDTO userLoginDTO) {
        User user = new User();
        UserLoginVO userLoginVO = new UserLoginVO();
        user = loginMapper.getByUsername(userLoginDTO.getUsername());
        if(user == null) {
            throw new RuntimeException("用户不存在！");
        }
        else if(!userLoginDTO.getPassword().equals(user.getPassword())) {
            throw new RuntimeException("密码错误！");
        }
        BeanUtils.copyProperties(user,userLoginVO);
        BaseContext.setCurrentId(user.getId());
        userLoginVO.setToken(jwtutil.createToken(user.getUsername(),user.getId()));
        // 登录成功，生成 token 并返回
        //userLoginVO.setToken(jwtutil.createToken(userLoginVO.getUsername(),userLoginVO.getId()));
        return userLoginVO;
    }
}
