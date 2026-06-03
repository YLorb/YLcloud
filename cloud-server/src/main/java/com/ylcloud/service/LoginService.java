package com.ylcloud.service;

import com.ylcloud.Exception.BaseException;
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

    /**
     * 校验用户登录凭证并生成登录返回对象。
     *
     * @param userLoginDTO 登录参数
     * @return 登录用户信息和令牌
     */
    public UserLoginVO login(UserLoginDTO userLoginDTO) {
        User user = loginMapper.getByUsername(userLoginDTO.getUsername());
        UserLoginVO userLoginVO = new UserLoginVO();
        if(user == null) {
            throw new BaseException("用户不存在！");
        }
        else if(!userLoginDTO.getPassword().equals(user.getPassword())) {
            throw new BaseException("密码错误！");
        }
        BeanUtils.copyProperties(user,userLoginVO);
        BaseContext.setCurrentId(user.getId());
        userLoginVO.setToken(jwtutil.createToken(user.getUsername(),user.getId()));
        return userLoginVO;
    }
}
