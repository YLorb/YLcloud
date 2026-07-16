package com.ylcloud.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.DTO.AdminPermissionGroupDTO;
import com.ylcloud.DTO.AdminUserAccessUpdateDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.Exception.ForbiddenException;
import com.ylcloud.VO.PermissionDefinitionVO;
import com.ylcloud.VO.PermissionGroupVO;
import com.ylcloud.constant.UserPermissionKeys;
import com.ylcloud.context.BaseContext;
import com.ylcloud.entity.PermissionGrant;
import com.ylcloud.entity.PermissionGroup;
import com.ylcloud.mapper.AccessControlMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AccessControlService {
    private final AccessControlMapper mapper;
    private final ObjectMapper objectMapper;

    public List<PermissionDefinitionVO> definitions() {
        return UserPermissionKeys.DEFINITIONS;
    }

    public List<PermissionGroupVO> listGroups() {
        Map<Long, Map<String, Boolean>> grants = new LinkedHashMap<>();
        for(PermissionGrant grant : mapper.listAllGroupGrants()) {
            grants.computeIfAbsent(grant.getSubjectId(), ignored -> UserPermissionKeys.defaults(false))
                    .put(grant.getPermissionKey(), grant.getAllowed() != null && grant.getAllowed() == 1);
        }
        return mapper.listGroups().stream().map(group -> toVO(group,grants.get(group.getId()))).toList();
    }

    @Transactional
    public PermissionGroupVO createGroup(AdminPermissionGroupDTO dto) {
        validateGroup(dto);
        PermissionGroup group = new PermissionGroup();
        group.setName(dto.getName().trim());
        group.setDescription(normalize(dto.getDescription()));
        group.setCreatedBy(BaseContext.getCurrentId());
        try {
            mapper.insertGroup(group);
        } catch(DuplicateKeyException ex) {
            throw new BaseException("用户组名称已存在");
        }
        replaceGroupGrants(group.getId(),dto.getPermissions());
        audit("GROUP",group.getId(),"CREATE",null,dto);
        return findGroup(group.getId());
    }

    @Transactional
    public PermissionGroupVO updateGroup(Long groupId, AdminPermissionGroupDTO dto) {
        validateGroup(dto);
        PermissionGroup before = requireGroup(groupId);
        try {
            mapper.updateGroup(groupId,dto.getName().trim(),normalize(dto.getDescription()));
        } catch(DuplicateKeyException ex) {
            throw new BaseException("用户组名称已存在");
        }
        replaceGroupGrants(groupId,dto.getPermissions());
        audit("GROUP",groupId,"UPDATE",before,dto);
        return findGroup(groupId);
    }

    @Transactional
    public void deleteGroup(Long groupId) {
        PermissionGroup group = requireGroup(groupId);
        if(group.getSystemGroup() != null && group.getSystemGroup() == 1) {
            throw new BaseException("系统默认用户组不能删除");
        }
        if(mapper.countGroupMembers(groupId) > 0) {
            throw new BaseException("请先移出该用户组中的所有用户");
        }
        mapper.deleteGroup(groupId);
        audit("GROUP",groupId,"DELETE",group,null);
    }

    @Transactional
    public void updateUserAccess(Long userId, AdminUserAccessUpdateDTO dto) {
        if(userId == null || dto == null) {
            throw new BaseException("用户权限参数不能为空");
        }
        Map<String, Object> before = userAccessSnapshot(userId);
        if(dto.isClearGroup()) {
            mapper.clearUserGroup(userId);
        } else if(dto.getGroupId() != null) {
            if(mapper.getGroup(dto.getGroupId()) == null) {
                throw new BaseException("用户组不存在");
            }
            mapper.assignUserGroup(userId,dto.getGroupId());
        }
        if(dto.getOverrides() != null) {
            validatePermissions(dto.getOverrides());
            mapper.deleteUserOverrides(userId);
            dto.getOverrides().forEach((key,allowed) -> mapper.insertUserOverride(userId,key,Boolean.TRUE.equals(allowed)));
        }
        audit("USER",userId,"ACCESS_UPDATE",before,userAccessSnapshot(userId));
    }

    public Map<String, Boolean> userOverrides(Long userId) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for(PermissionGrant grant : mapper.listUserOverrides(userId)) {
            result.put(grant.getPermissionKey(),grant.getAllowed() != null && grant.getAllowed() == 1);
        }
        return result;
    }

    public Map<String, Boolean> effectivePermissions(Long userId) {
        Map<String, Boolean> effective = new LinkedHashMap<>();
        for(PermissionDefinitionVO definition : UserPermissionKeys.DEFINITIONS) {
            effective.put(definition.getKey(),resolve(userId,definition.getKey()));
        }
        if(!Boolean.TRUE.equals(effective.get(UserPermissionKeys.CLOUD_DRIVE))) {
            effective.replaceAll((key,value) -> UserPermissionKeys.CLOUD_DRIVE.equals(key) ? value : false);
        }
        return effective;
    }

    public void require(Long userId, String permissionKey) {
        if(!UserPermissionKeys.ALL.contains(permissionKey)) {
            throw new IllegalArgumentException("Unknown permission: " + permissionKey);
        }
        if(!resolve(userId,UserPermissionKeys.CLOUD_DRIVE)
                || (!UserPermissionKeys.CLOUD_DRIVE.equals(permissionKey) && !resolve(userId,permissionKey))) {
            throw new ForbiddenException("权限不足: " + permissionKey);
        }
    }

    private boolean resolve(Long userId, String permissionKey) {
        Integer allowed = mapper.resolvePermission(userId,permissionKey);
        return allowed != null && allowed == 1;
    }

    private void replaceGroupGrants(Long groupId, Map<String, Boolean> permissions) {
        validatePermissions(permissions);
        mapper.deleteGroupGrants(groupId);
        Map<String, Boolean> normalized = UserPermissionKeys.defaults(false);
        if(permissions != null) normalized.putAll(permissions);
        normalized.forEach((key,allowed) -> mapper.insertGroupGrant(groupId,key,Boolean.TRUE.equals(allowed)));
    }

    private PermissionGroupVO findGroup(Long groupId) {
        return listGroups().stream().filter(group -> groupId.equals(group.getId())).findFirst()
                .orElseThrow(() -> new BaseException("用户组不存在"));
    }

    private PermissionGroup requireGroup(Long groupId) {
        PermissionGroup group = groupId == null ? null : mapper.lockGroup(groupId);
        if(group == null) throw new BaseException("用户组不存在");
        return group;
    }

    private PermissionGroupVO toVO(PermissionGroup group, Map<String, Boolean> permissions) {
        PermissionGroupVO vo = new PermissionGroupVO();
        vo.setId(group.getId());
        vo.setName(group.getName());
        vo.setDescription(group.getDescription());
        vo.setSystemGroup(group.getSystemGroup() != null && group.getSystemGroup() == 1);
        vo.setUserCount(group.getUserCount() == null ? 0 : group.getUserCount());
        vo.setPermissions(permissions == null ? UserPermissionKeys.defaults(false) : permissions);
        vo.setCreateTime(group.getCreateTime());
        vo.setUpdateTime(group.getUpdateTime());
        return vo;
    }

    private void validateGroup(AdminPermissionGroupDTO dto) {
        if(dto == null || dto.getName() == null || dto.getName().isBlank()) throw new BaseException("用户组名称不能为空");
        validatePermissions(dto.getPermissions());
    }

    private void validatePermissions(Map<String, Boolean> permissions) {
        if(permissions == null) return;
        String invalid = permissions.keySet().stream().filter(key -> !UserPermissionKeys.ALL.contains(key)).findFirst().orElse(null);
        if(invalid != null) throw new BaseException("不支持的权限: " + invalid);
    }

    private Map<String, Object> userAccessSnapshot(Long userId) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("groupId",mapper.getUserGroupId(userId));
        snapshot.put("overrides",userOverrides(userId));
        return snapshot;
    }

    private String normalize(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private void audit(String targetType, Long targetId, String action, Object before, Object after) {
        mapper.insertAudit(BaseContext.getCurrentId(),targetType,targetId,action,json(before),json(after));
    }
    private String json(Object value) {
        if(value == null) return null;
        try { return objectMapper.writeValueAsString(value); }
        catch(JsonProcessingException ex) { return String.valueOf(value); }
    }
}
