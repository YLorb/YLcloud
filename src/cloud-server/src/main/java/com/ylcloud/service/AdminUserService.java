package com.ylcloud.service;

import com.ylcloud.DTO.AdminUserAccessUpdateDTO;
import com.ylcloud.DTO.AdminUserCreateDTO;
import com.ylcloud.DTO.AdminUserUpdateDTO;
import com.ylcloud.DTO.UserRegisterDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.VO.AdminUserVO;
import com.ylcloud.context.BaseContext;
import com.ylcloud.entity.User;
import com.ylcloud.mapper.AdminUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AdminUserService {
    private static final Set<String> ROLES = Set.of("ADMIN", "USER");
    private static final Set<Integer> STATUSES = Set.of(0, 1);

    private final AdminUserMapper adminUserMapper;
    private final AccessControlService accessControlService;
    private final SignService signService;

    public List<AdminUserVO> list() {
        return adminUserMapper.listAll().stream().map(this::enrich).toList();
    }

    @Transactional
    public AdminUserVO create(AdminUserCreateDTO dto) {
        if(dto == null) throw new BaseException("用户信息不能为空");
        String role = dto.getRole() == null || dto.getRole().isBlank() ? "USER" : dto.getRole().trim().toUpperCase(Locale.ROOT);
        if(!ROLES.contains(role)) throw new BaseException("用户角色仅支持 ADMIN 或 USER");
        UserRegisterDTO registration = new UserRegisterDTO();
        registration.setUsername(dto.getUsername().trim());
        registration.setPassword(dto.getPassword());
        registration.setNickname(dto.getNickname().trim());
        Long userId = signService.createByAdmin(registration,role,dto.getEmail());
        if(dto.getGroupId() != null) {
            AdminUserAccessUpdateDTO access = new AdminUserAccessUpdateDTO();
            access.setGroupId(dto.getGroupId());
            accessControlService.updateUserAccess(userId,access);
        }
        return get(userId);
    }

    @Transactional
    public AdminUserVO update(Long userId, AdminUserUpdateDTO dto) {
        if(userId == null || dto == null || (dto.getRole() == null && dto.getStatus() == null)) {
            throw new BaseException("至少提供一项用户权限修改");
        }
        User target = adminUserMapper.lockById(userId);
        if(target == null) {
            throw new BaseException("用户不存在");
        }
        Long operatorId = BaseContext.getCurrentId();
        if(userId.equals(operatorId)) {
            throw new BaseException("不能在当前会话中修改自己的角色或状态");
        }
        String nextRole = target.getRole();
        if(dto.getRole() != null) {
            nextRole = dto.getRole().trim().toUpperCase(Locale.ROOT);
            if(!ROLES.contains(nextRole)) {
                throw new BaseException("用户角色仅支持 ADMIN 或 USER");
            }
        }
        Integer nextStatus = dto.getStatus() == null ? target.getStatus() : dto.getStatus();
        if(!STATUSES.contains(nextStatus)) {
            throw new BaseException("用户状态仅支持启用或停用");
        }
        if(Boolean.TRUE.equals(target.getDeploymentOwner())
                && (!"ADMIN".equals(nextRole) || nextStatus == 0)) {
            throw new BaseException("部署所有者不可降级或停用");
        }
        boolean removingActiveAdmin = "ADMIN".equalsIgnoreCase(target.getRole())
                && target.getStatus() != null && target.getStatus() == 1
                && (!"ADMIN".equals(nextRole) || nextStatus == 0);
        if(removingActiveAdmin && adminUserMapper.countActiveAdmins() <= 1) {
            throw new BaseException("系统必须保留至少一个启用的管理员");
        }
        if(dto.getRole() != null && adminUserMapper.updateRole(userId,nextRole) == 0) {
            throw new BaseException("更新用户角色失败");
        }
        if(dto.getStatus() != null && adminUserMapper.updateStatus(userId,nextStatus) == 0) {
            throw new BaseException("更新用户状态失败");
        }
        return get(userId);
    }

    @Transactional
    public AdminUserVO updateAccess(Long userId, AdminUserAccessUpdateDTO dto) {
        if(adminUserMapper.lockById(userId) == null) throw new BaseException("用户不存在");
        accessControlService.updateUserAccess(userId,dto);
        return get(userId);
    }

    private AdminUserVO get(Long userId) {
        AdminUserVO user = adminUserMapper.getView(userId);
        if(user == null) throw new BaseException("用户不存在");
        return enrich(user);
    }

    private AdminUserVO enrich(AdminUserVO user) {
        user.setPermissionOverrides(accessControlService.userOverrides(user.getId()));
        user.setEffectivePermissions(accessControlService.effectivePermissions(user.getId()));
        return user;
    }
}
