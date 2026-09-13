package com.ylcloud.scheduler;

import com.ylcloud.service.AccountLifecycleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * TASK-010: 账号生命周期定时任务。
 * 扫描过期注销账号并提交删除任务。
 */
@Component
@Slf4j
public class AccountLifecycleScheduler {
    private final AccountLifecycleService lifecycleService;

    public AccountLifecycleScheduler(AccountLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    /**
     * 每小时扫描一次过期注销账号。
     */
    @Scheduled(fixedDelayString = "${ylcloud.account.scan-delay-ms:3600000}")
    public void scanExpiredAccounts() {
        try {
            int submitted = lifecycleService.scanAndSubmitDeletions();
            if (submitted > 0) {
                log.info("Account lifecycle scan completed: {} deletion jobs submitted", submitted);
            }
        } catch (Exception e) {
            log.error("Account lifecycle scan failed", e);
        }
    }
}
