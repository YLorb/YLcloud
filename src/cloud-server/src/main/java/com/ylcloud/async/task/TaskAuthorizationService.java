package com.ylcloud.async.task;

import com.ylcloud.Exception.ForbiddenException;
import com.ylcloud.constant.SpaceConstant;
import com.ylcloud.entity.SpaceMember;
import com.ylcloud.entity.UnifiedAsyncTask;
import com.ylcloud.entity.User;
import com.ylcloud.mapper.LoginMapper;
import com.ylcloud.mapper.SpaceMemberMapper;
import org.springframework.stereotype.Service;

@Service
public class TaskAuthorizationService {
    private final LoginMapper loginMapper;
    private final SpaceMemberMapper memberMapper;

    public TaskAuthorizationService(LoginMapper loginMapper, SpaceMemberMapper memberMapper) {
        this.loginMapper = loginMapper;
        this.memberMapper = memberMapper;
    }

    public void requireView(UnifiedAsyncTask task, Long userId) {
        if(task.getCreatedBy() != null && task.getCreatedBy().equals(userId)) return;
        if(task.getSpaceId() != null && memberMapper.getActive(task.getSpaceId(),userId) != null) return;
        User user = loginMapper.getById(userId);
        if(user != null && Boolean.TRUE.equals(user.getDeploymentOwner())) return;
        throw new ForbiddenException("无权查看该任务");
    }

    public void requireOperate(UnifiedAsyncTask task, Long userId) {
        if(task.getCreatedBy() != null && task.getCreatedBy().equals(userId)) return;
        if(task.getSpaceId() != null) {
            SpaceMember member = memberMapper.getActive(task.getSpaceId(),userId);
            if(member != null && (SpaceConstant.ROLE_OWNER.equals(member.getRole())
                    || SpaceConstant.ROLE_ADMIN.equals(member.getRole()))) return;
        }
        User user = loginMapper.getById(userId);
        if(user != null && "ADMIN".equalsIgnoreCase(user.getRole())) return;
        throw new ForbiddenException("无权重试或取消该任务");
    }
}
