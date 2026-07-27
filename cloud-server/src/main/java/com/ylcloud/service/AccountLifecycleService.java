package com.ylcloud.service;

import com.ylcloud.DTO.AccountCancelDTO;
import com.ylcloud.DTO.AccountRecoverDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.Exception.ConflictException;
import com.ylcloud.Exception.ForbiddenException;
import com.ylcloud.Exception.NotFoundException;
import com.ylcloud.VO.AccountStatusVO;
import com.ylcloud.entity.AccountRecoveryLog;
import com.ylcloud.entity.User;
import com.ylcloud.mapper.AccessControlMapper;
import com.ylcloud.mapper.AccountRecoveryLogMapper;
import com.ylcloud.mapper.UserLifecycleMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 账号生命周期服务：注销、恢复、状态查询。
 * 注销立即禁止登录，默认3天ADMIN恢复期，到期后提交异步删除。
 */
@Service
@Slf4j
public class AccountLifecycleService {
    private final UserLifecycleMapper userLifecycleMapper;
    private final AccountRecoveryLogMapper recoveryLogMapper;
    private final AccessControlMapper auditMapper;
    private final AccountDeletionOrchestrationService deletionService;

    @Value("${ylcloud.account.recovery-days:3}")
    private int recoveryDays;

    public AccountLifecycleService(UserLifecycleMapper userLifecycleMapper,
                                   AccountRecoveryLogMapper recoveryLogMapper,
                                   AccessControlMapper auditMapper,
                                   AccountDeletionOrchestrationService deletionService) {
        this.userLifecycleMapper = userLifecycleMapper;
        this.recoveryLogMapper = recoveryLogMapper;
        this.auditMapper = auditMapper;
        this.deletionService = deletionService;
    }

    /**
     * 用户请求注销账号。
     * 前置校验：TEAM OWNER 必须先转让或解散团队。
     */
    @Transactional
    public AccountStatusVO requestCancellation(Long userId, AccountCancelDTO dto) {
        User user = userLifecycleMapper.lockById(userId);
        if (user == null) throw new NotFoundException("用户不存在");

        String accountStatus = getAccountStatus(user);
        if (!"ACTIVE".equals(accountStatus)) {
            throw new ConflictException("账号当前状态不允许注销: " + accountStatus);
        }

        // 部署所有者不能注销
        if (Boolean.TRUE.equals(user.getDeploymentOwner())) {
            throw new ForbiddenException("部署所有者不能注销账号");
        }

        // TEAM OWNER 前置校验
        int ownedTeams = userLifecycleMapper.countOwnedTeams(userId);
        if (ownedTeams > 0) {
            if (!Boolean.TRUE.equals(dto.getConfirmTeamOwnerTransfer())) {
                throw new ConflictException("您是 " + ownedTeams + " 个团队的 OWNER，请先转让或解散团队");
            }
            // 即使确认，也需要先处理团队
            List<Long> teamIds = userLifecycleMapper.listOwnedTeamIds(userId);
            throw new ConflictException("请先转让或解散以下团队: " + teamIds);
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime recoverableUntil = now.plusDays(recoveryDays);

        int updated = userLifecycleMapper.markCancelled(userId, now, userId, recoverableUntil, now);
        if (updated != 1) throw new ConflictException("账号状态已变化，请刷新重试");

        auditMapper.insertAudit(userId, "ACCOUNT", userId, "CANCEL_REQUEST",
                "{\"accountStatus\":\"ACTIVE\"}",
                "{\"accountStatus\":\"CANCELLED\",\"recoverableUntil\":\"" + recoverableUntil + "\"}");

        log.info("Account cancellation requested: userId={}, recoverableUntil={}", userId, recoverableUntil);
        return getStatus(userId);
    }

    /**
     * ADMIN 恢复已注销账号（仅限恢复期内）。
     */
    @Transactional
    public AccountStatusVO recoverAccount(Long adminId, AccountRecoverDTO dto) {
        User admin = userLifecycleMapper.lockById(adminId);
        if (admin == null || !"ADMIN".equals(admin.getRole())) {
            throw new ForbiddenException("仅 ADMIN 可执行恢复操作");
        }

        User target = userLifecycleMapper.lockById(dto.getUserId());
        if (target == null) throw new NotFoundException("目标用户不存在");

        String accountStatus = getAccountStatus(target);
        if (!"CANCELLED".equals(accountStatus)) {
            throw new ConflictException("只有 CANCELLED 状态的账号可以恢复");
        }

        // 检查恢复期
        LocalDateTime recoverableUntil = target.getRecoverableUntil();
        if (recoverableUntil != null && recoverableUntil.isBefore(LocalDateTime.now())) {
            throw new ConflictException("恢复期已过，账号已进入删除流程");
        }

        int updated = userLifecycleMapper.recoverFromCancelled(dto.getUserId(), LocalDateTime.now());
        if (updated != 1) throw new ConflictException("账号状态已变化");

        // 记录恢复日志
        AccountRecoveryLog recoveryLog = new AccountRecoveryLog();
        recoveryLog.setUserId(dto.getUserId());
        recoveryLog.setRecoveredBy(adminId);
        recoveryLog.setPreviousStatus("CANCELLED");
        recoveryLog.setRecoveryReason(dto.getReason());
        recoveryLog.setCreatedAt(LocalDateTime.now());
        recoveryLogMapper.insert(recoveryLog);

        auditMapper.insertAudit(adminId, "ACCOUNT", dto.getUserId(), "ACCOUNT_RECOVER",
                "{\"accountStatus\":\"CANCELLED\"}",
                "{\"accountStatus\":\"ACTIVE\",\"reason\":\"" + safe(dto.getReason()) + "\"}");

        log.info("Account recovered: userId={}, by adminId={}", dto.getUserId(), adminId);
        return getStatus(dto.getUserId());
    }

    /**
     * 获取账号状态。
     */
    public AccountStatusVO getStatus(Long userId) {
        User user = userLifecycleMapper.getAccountStatus(userId);
        if (user == null) throw new NotFoundException("用户不存在");

        AccountStatusVO vo = new AccountStatusVO();
        vo.setUserId(userId);
        vo.setUsername(user.getUsername());
        vo.setAccountStatus(getAccountStatus(user));
        vo.setCancelledAt(user.getCancelledAt());
        vo.setRecoverableUntil(user.getRecoverableUntil());
        vo.setPurgingStartedAt(user.getPurgingStartedAt());
        vo.setPurgedAt(user.getPurgedAt());

        String status = getAccountStatus(user);
        vo.setCanRecover("CANCELLED".equals(status) &&
                (user.getRecoverableUntil() == null || user.getRecoverableUntil().isAfter(LocalDateTime.now())));
        vo.setIsTeamOwner(userLifecycleMapper.countOwnedTeams(userId) > 0);
        vo.setOwnedTeamCount(userLifecycleMapper.countOwnedTeams(userId));

        return vo;
    }

    /**
     * 扫描过期注销账号并提交删除任务。
     * 由定时任务调用。
     */
    @Transactional
    public int scanAndSubmitDeletions() {
        LocalDateTime now = LocalDateTime.now();
        List<Long> expiredUsers = userLifecycleMapper.listExpiredCancelledUsers(now, 100);
        int submitted = 0;

        for (Long userId : expiredUsers) {
            try {
                User user = userLifecycleMapper.lockById(userId);
                if (user == null || !"CANCELLED".equals(getAccountStatus(user))) continue;

                // 标记为 PURGING
                if (userLifecycleMapper.markPurging(userId, now, now) != 1) continue;

                // 提交异步删除任务
                deletionService.submitDeletionJob(userId);
                submitted++;

                auditMapper.insertAudit(userId, "ACCOUNT", userId, "PURGE_SUBMIT",
                        "{\"accountStatus\":\"CANCELLED\"}",
                        "{\"accountStatus\":\"PURGING\"}");

                log.info("Account purge submitted: userId={}", userId);
            } catch (Exception e) {
                log.error("Failed to submit deletion for userId={}", userId, e);
            }
        }

        return submitted;
    }

    /**
     * 检查用户账号是否活跃（用于拦截器）。
     */
    public boolean isAccountActive(Long userId) {
        User user = userLifecycleMapper.getAccountStatus(userId);
        if (user == null) return false;
        return "ACTIVE".equals(getAccountStatus(user));
    }

    private String getAccountStatus(User user) {
        // 兼容旧数据：如果 account_status 为空，根据 status 推断
        if (user.getAccountStatus() != null) {
            return user.getAccountStatus();
        }
        return Integer.valueOf(1).equals(user.getStatus()) ? "ACTIVE" : "CANCELLED";
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "未填写" : value.trim();
    }
}
