package com.ylcloud.service;

import com.ylcloud.DTO.AdminResourceGrantCreateDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.Exception.ForbiddenException;
import com.ylcloud.VO.AdminResourceGrantVO;
import com.ylcloud.authorization.ResourceAction;
import com.ylcloud.authorization.ResourceType;
import com.ylcloud.constant.SpaceConstant;
import com.ylcloud.constant.StatusConstant;
import com.ylcloud.context.BaseContext;
import com.ylcloud.entity.AdminResourceGrant;
import com.ylcloud.entity.Space;
import com.ylcloud.entity.SpaceMember;
import com.ylcloud.entity.User;
import com.ylcloud.mapper.AccessControlMapper;
import com.ylcloud.mapper.AdminResourceGrantMapper;
import com.ylcloud.mapper.LoginMapper;
import com.ylcloud.mapper.SpaceMapper;
import com.ylcloud.mapper.SpaceMemberMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AdminResourceGrantService {
    private static final Set<ResourceAction> GRANTABLE_ACTIONS =
            EnumSet.of(ResourceAction.READ,ResourceAction.DOWNLOAD);

    private final AdminResourceGrantMapper grantMapper;
    private final LoginMapper loginMapper;
    private final SpaceMapper spaceMapper;
    private final SpaceMemberMapper spaceMemberMapper;
    private final AccessControlMapper accessControlMapper;

    @Transactional
    public List<AdminResourceGrantVO> grant(AdminResourceGrantCreateDTO dto) {
        Long grantorId = requireCurrentUser();
        if(dto == null || dto.getAdminUserId() == null || dto.getResourceId() == null
                || dto.getActions() == null || dto.getActions().isEmpty()) {
            throw new BaseException("资源授权参数不能为空");
        }
        User admin = loginMapper.getById(dto.getAdminUserId());
        if(admin == null || !StatusConstant.ENABLE.equals(admin.getStatus())
                || !"ADMIN".equalsIgnoreCase(admin.getRole())) {
            throw new BaseException("授权目标必须是启用的管理员");
        }
        if(Boolean.TRUE.equals(admin.getDeploymentOwner())) {
            throw new BaseException("部署所有者无需资源授权");
        }
        ResourceType resourceType = resourceType(dto.getResourceType());
        validateGrantor(grantorId,resourceType,dto.getResourceId());
        Set<ResourceAction> actions = actions(dto.getActions());
        for(ResourceAction action : actions) {
            grantMapper.activate(grantorId,admin.getId(),resourceType.name(),dto.getResourceId(),action.name());
        }
        accessControlMapper.insertAudit(
                grantorId,
                resourceType.name(),
                dto.getResourceId(),
                "ADMIN_RESOURCE_GRANT",
                null,
                "adminUserId=" + admin.getId() + ",actions=" + actions
        );
        return grantMapper.listScope(
                grantorId,admin.getId(),resourceType.name(),dto.getResourceId()
        ).stream().map(this::toVO).toList();
    }

    public List<AdminResourceGrantVO> listGrantedByMe() {
        return grantMapper.listGrantedBy(requireCurrentUser()).stream().map(this::toVO).toList();
    }

    public List<AdminResourceGrantVO> listReceived() {
        Long userId = requireCurrentUser();
        User user = loginMapper.getById(userId);
        if(user == null || !"ADMIN".equalsIgnoreCase(user.getRole())) {
            throw new ForbiddenException("只有管理员可以查看收到的资源授权");
        }
        return grantMapper.listReceivedBy(userId).stream().map(this::toVO).toList();
    }

    @Transactional
    public void revoke(Long grantId) {
        Long grantorId = requireCurrentUser();
        AdminResourceGrant grant = grantId == null ? null : grantMapper.lockById(grantId);
        if(grant == null || grant.getStatus() == null || grant.getStatus() != 1) {
            throw new BaseException("资源授权不存在或已撤销");
        }
        if(!grantorId.equals(grant.getGrantorId())) {
            throw new ForbiddenException("只能撤销自己创建的资源授权");
        }
        if(grantMapper.revoke(grantId) == 0) {
            throw new BaseException("资源授权撤销失败");
        }
        accessControlMapper.insertAudit(
                grantorId,
                grant.getResourceType(),
                grant.getResourceId(),
                "ADMIN_RESOURCE_REVOKE",
                "adminUserId=" + grant.getAdminUserId() + ",action=" + grant.getAction(),
                null
        );
    }

    private void validateGrantor(Long grantorId, ResourceType resourceType, Long resourceId) {
        if(resourceId == null || resourceId < 1) {
            throw new BaseException("资源 ID 无效");
        }
        if(ResourceType.USER_PRIVATE.equals(resourceType)) {
            if(!grantorId.equals(resourceId)) {
                throw new ForbiddenException("只能授权访问自己的私人资源");
            }
            return;
        }
        Space space = spaceMapper.getById(resourceId);
        SpaceMember member = spaceMemberMapper.getActive(resourceId,grantorId);
        if(space == null || member == null || !SpaceConstant.ROLE_OWNER.equals(member.getRole())) {
            throw new ForbiddenException("只有 Space OWNER 可以授权管理员访问");
        }
    }

    private ResourceType resourceType(String value) {
        try {
            return ResourceType.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch(IllegalArgumentException ex) {
            throw new BaseException("资源类型仅支持 USER_PRIVATE 或 SPACE");
        }
    }

    private Set<ResourceAction> actions(Set<String> values) {
        EnumSet<ResourceAction> actions = EnumSet.noneOf(ResourceAction.class);
        try {
            for(String value : values) {
                actions.add(ResourceAction.valueOf(value.trim().toUpperCase(Locale.ROOT)));
            }
        } catch(RuntimeException ex) {
            throw new BaseException("资源动作仅支持 READ 或 DOWNLOAD");
        }
        if(actions.isEmpty() || !GRANTABLE_ACTIONS.containsAll(actions)) {
            throw new BaseException("资源动作仅支持 READ 或 DOWNLOAD");
        }
        return actions;
    }

    private Long requireCurrentUser() {
        Long userId = BaseContext.getCurrentId();
        if(userId == null) throw new ForbiddenException("请先登录");
        return userId;
    }

    private AdminResourceGrantVO toVO(AdminResourceGrant grant) {
        AdminResourceGrantVO vo = new AdminResourceGrantVO();
        vo.setId(grant.getId());
        vo.setGrantorId(grant.getGrantorId());
        vo.setAdminUserId(grant.getAdminUserId());
        vo.setResourceType(grant.getResourceType());
        vo.setResourceId(grant.getResourceId());
        vo.setAction(grant.getAction());
        vo.setCreateTime(grant.getCreateTime());
        vo.setUpdateTime(grant.getUpdateTime());
        return vo;
    }
}
