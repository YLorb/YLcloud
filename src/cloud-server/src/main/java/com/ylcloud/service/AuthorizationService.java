package com.ylcloud.service;

import com.ylcloud.Exception.ForbiddenException;
import com.ylcloud.authorization.AccessSubject;
import com.ylcloud.authorization.AuthorizationBasis;
import com.ylcloud.authorization.ResourceAction;
import com.ylcloud.authorization.ResourceType;
import com.ylcloud.constant.SpaceConstant;
import com.ylcloud.constant.StatusConstant;
import com.ylcloud.entity.SpaceMember;
import com.ylcloud.entity.User;
import com.ylcloud.mapper.AccessControlMapper;
import com.ylcloud.mapper.AdminResourceGrantMapper;
import com.ylcloud.mapper.LoginMapper;
import com.ylcloud.mapper.SpaceMemberMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthorizationService {
    private final LoginMapper loginMapper;
    private final SpaceMemberMapper spaceMemberMapper;
    private final AdminResourceGrantMapper grantMapper;
    private final AccessControlMapper accessControlMapper;

    public AuthorizationBasis require(AccessSubject subject,
                                      ResourceType resourceType,
                                      Long resourceId,
                                      ResourceAction action) {
        if(subject == null || resourceType == null || resourceId == null || resourceId < 1 || action == null) {
            throw new ForbiddenException("资源授权参数无效");
        }
        User user = loginMapper.getById(subject.userId());
        if(user == null || !StatusConstant.ENABLE.equals(user.getStatus())) {
            throw new ForbiddenException("授权主体无效或已停用");
        }

        AuthorizationBasis direct = directAccess(subject,resourceType,resourceId,action);
        if(direct != null) {
            return direct;
        }
        if(Boolean.TRUE.equals(user.getDeploymentOwner())) {
            auditOwnerOverride(subject,resourceType,resourceId,action);
            return AuthorizationBasis.DEPLOYMENT_OWNER;
        }
        if("ADMIN".equalsIgnoreCase(user.getRole())
                && grantMapper.countActive(user.getId(),resourceType.name(),resourceId,action.name()) > 0) {
            return AuthorizationBasis.ADMIN_RESOURCE_GRANT;
        }
        throw new ForbiddenException("没有资源访问权限");
    }

    private AuthorizationBasis directAccess(AccessSubject subject,
                                            ResourceType resourceType,
                                            Long resourceId,
                                            ResourceAction action) {
        if(ResourceType.USER_PRIVATE.equals(resourceType)) {
            return resourceId.equals(subject.userId()) ? AuthorizationBasis.SELF : null;
        }
        SpaceMember member = spaceMemberMapper.getActive(resourceId,subject.userId());
        if(member == null) {
            return null;
        }
        if(ResourceAction.READ.equals(action) || ResourceAction.DOWNLOAD.equals(action)
                || ResourceAction.EXECUTE.equals(action)) {
            return AuthorizationBasis.SPACE_MEMBER;
        }
        if(SpaceConstant.ROLE_OWNER.equals(member.getRole())
                || SpaceConstant.ROLE_ADMIN.equals(member.getRole())) {
            return AuthorizationBasis.SPACE_MEMBER;
        }
        return null;
    }

    private void auditOwnerOverride(AccessSubject subject,
                                    ResourceType resourceType,
                                    Long resourceId,
                                    ResourceAction action) {
        try {
            accessControlMapper.insertAudit(
                    subject.userId(),
                    resourceType.name(),
                    resourceId,
                    "DEPLOYMENT_OWNER_" + action.name() + "_" + subject.type().name(),
                    null,
                    null
            );
        } catch(RuntimeException ex) {
            // Owner access cannot be blocked by audit storage failure, but the
            // failure remains visible for operations and the later audit task.
            log.error("deployment owner authorization audit failed: resourceType={}, resourceId={}, action={}",
                    resourceType,resourceId,action,ex);
        }
    }
}
