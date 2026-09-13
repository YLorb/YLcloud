package com.ylcloud.service;

import com.ylcloud.Exception.BaseException;
import com.ylcloud.Exception.ConflictException;
import com.ylcloud.mapper.AdminTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminTaskService {
    private final AdminTaskMapper mapper;
    private final SecurityAuditService audit;

    public record Page(List<Map<String,Object>> items, long total, int page, int pageSize) {}

    public Page page(boolean archived, String status, String type, Long creator, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) throw new BaseException("页码必须大于零，每页最多 100 条");
        if ((status != null && status.length() > 30) || (type != null && type.length() > 80)) throw new BaseException("任务筛选参数过长");
        String normalizedStatus = status == null || status.isBlank() ? null : status.trim();
        String normalizedType = type == null || type.isBlank() ? null : type.trim();
        return new Page(mapper.page(archived, normalizedStatus, normalizedType, creator, pageSize, (long)(page - 1) * pageSize),
                mapper.count(archived, normalizedStatus, normalizedType, creator), page, pageSize);
    }

    @Transactional
    public void archive(Long id, Long operator) {
        if (mapper.archive(id, operator) != 1) throw new ConflictException("任务不存在、已归档或尚未结束，请刷新列表");
        audit.recordCritical(new SecurityAuditService.AuditEventBuilder().eventType("ASYNC_TASK")
                .action("ARCHIVE").subject(operator, null).target("TASK", id.toString(), null).result("SUCCESS"));
    }
}
