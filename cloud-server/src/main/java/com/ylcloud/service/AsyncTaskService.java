package com.ylcloud.service;

import com.ylcloud.VO.AsyncTaskVO;
import com.ylcloud.VO.AsyncTaskDetailVO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.Exception.NotFoundException;
import com.ylcloud.entity.SpaceKnowledgePipelineTask;
import com.ylcloud.entity.SpaceRagTask;
import com.ylcloud.mapper.SpaceKnowledgePipelineTaskMapper;
import com.ylcloud.mapper.SpaceRagTaskMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 聚合真正由后台执行器运行的任务。
 *
 * <p>普通上传、分片上传和当前仍在请求线程执行的分片合并不属于此列表。</p>
 */
@Service
public class AsyncTaskService {
    private static final int TASK_LIMIT_PER_SOURCE = 100;

    private final SpaceRagTaskMapper ragTaskMapper;
    private final SpaceKnowledgePipelineTaskMapper knowledgeTaskMapper;

    public AsyncTaskService(SpaceRagTaskMapper ragTaskMapper,
                            SpaceKnowledgePipelineTaskMapper knowledgeTaskMapper) {
        this.ragTaskMapper = ragTaskMapper;
        this.knowledgeTaskMapper = knowledgeTaskMapper;
    }

    public List<AsyncTaskVO> listUserTasks(Long userId, Long spaceId) {
        List<AsyncTaskVO> tasks = new ArrayList<>();
        ragTaskMapper.listByCreatedBy(userId,spaceId,TASK_LIMIT_PER_SOURCE)
                .forEach(task -> tasks.add(fromRag(task)));
        knowledgeTaskMapper.listByCreatedBy(userId,spaceId,TASK_LIMIT_PER_SOURCE)
                .forEach(task -> tasks.add(fromKnowledge(task)));
        tasks.sort(Comparator.comparing(AsyncTaskVO::getCreateTime,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return tasks;
    }

    public AsyncTaskDetailVO getUserTask(Long userId, String source, Long taskId) {
        if(taskId == null || source == null) {
            throw new BaseException("任务来源和任务 ID 不能为空");
        }
        if("rag".equalsIgnoreCase(source)) {
            SpaceRagTask task = ragTaskMapper.getById(taskId);
            if(task == null || !userId.equals(task.getCreatedBy())) throw new NotFoundException("后台任务不存在");
            return detailFromRag(task);
        }
        if("knowledge".equalsIgnoreCase(source)) {
            SpaceKnowledgePipelineTask task = knowledgeTaskMapper.getById(taskId);
            if(task == null || !userId.equals(task.getCreatedBy())) throw new NotFoundException("后台任务不存在");
            return detailFromKnowledge(task);
        }
        throw new BaseException("不支持的后台任务来源");
    }

    private AsyncTaskVO fromRag(SpaceRagTask task) {
        AsyncTaskVO vo = base("rag",task.getId(),task.getSpaceId(),task.getDocumentId(),
                task.getTaskType(),ragTitle(task.getTaskType()),task.getTaskStatus(),safeError(task.getErrorMessage()),
                task.getCreatetime(),task.getUpdatetime());
        int total = value(task.getTotalCount());
        int current = value(task.getSuccessCount()) + value(task.getFailedCount());
        vo.setTotal(total);
        vo.setCurrent(current);
        vo.setProgress(progress(task.getTaskStatus(),current,total));
        vo.setPhase(task.getTaskStatus());
        vo.setMessage(ragMessage(task,current,total));
        vo.setRetryable("FAILED".equalsIgnoreCase(task.getTaskStatus()));
        return vo;
    }

    private AsyncTaskVO fromKnowledge(SpaceKnowledgePipelineTask task) {
        AsyncTaskVO vo = base("knowledge",task.getId(),task.getSpaceId(),task.getDocumentId(),
                task.getTaskType(),task.getDocumentId() == null ? "空间知识流水线" : "文档知识流水线",
                task.getTaskStatus(),safeError(task.getErrorMessage()),task.getCreatetime(),task.getUpdatetime());
        vo.setTotal(value(task.getTotalCount()));
        vo.setCurrent(value(task.getSuccessCount()) + value(task.getFailedCount()));
        vo.setProgress(task.getProgress() == null ? progress(task.getTaskStatus(),vo.getCurrent(),vo.getTotal()) : task.getProgress());
        vo.setPhase(firstNonBlank(task.getTerminalStage(),task.getStage(),task.getTaskStatus()));
        vo.setMessage(safeError(firstNonBlank(task.getTerminalReason(),task.getIncrementalDetail(),vo.getPhase())));
        vo.setRetryable("FAILED".equalsIgnoreCase(task.getTaskStatus()) ||
                "PARTIAL_SUCCESS".equalsIgnoreCase(task.getTaskStatus()));
        return vo;
    }

    private AsyncTaskDetailVO detailFromRag(SpaceRagTask task) {
        AsyncTaskDetailVO detail = detailBase(fromRag(task),task.getSuccessCount(),task.getFailedCount(),
                task.getStartedTime(),task.getFinishedTime());
        detail.setTerminalStage(task.getTaskStatus());
        detail.setTerminalReason(safeError(task.getErrorMessage()));
        detail.setCompletionSummary(summary(task.getTaskStatus(),task.getSuccessCount(),task.getFailedCount(),task.getErrorMessage()));
        detail.setErrorMessage(safeError(task.getErrorMessage()));
        return detail;
    }

    private AsyncTaskDetailVO detailFromKnowledge(SpaceKnowledgePipelineTask task) {
        AsyncTaskDetailVO detail = detailBase(fromKnowledge(task),task.getSuccessCount(),task.getFailedCount(),
                task.getStartedTime(),task.getFinishedTime());
        detail.setTerminalStage(firstNonBlank(task.getTerminalStage(),task.getStage(),task.getTaskStatus()));
        detail.setTerminalReason(safeError(firstNonBlank(task.getErrorMessage(),task.getTerminalReason(),task.getIncrementalDetail())));
        detail.setCompletionSummary(summary(task.getTaskStatus(),task.getSuccessCount(),task.getFailedCount(),task.getErrorMessage()));
        detail.setErrorMessage(safeError(task.getErrorMessage()));
        return detail;
    }

    private AsyncTaskDetailVO detailBase(AsyncTaskVO source, Integer successCount, Integer failedCount,
                                         LocalDateTime startedTime, LocalDateTime finishedTime) {
        AsyncTaskDetailVO detail = new AsyncTaskDetailVO();
        org.springframework.beans.BeanUtils.copyProperties(source,detail);
        detail.setSuccessCount(value(successCount));
        detail.setFailedCount(value(failedCount));
        detail.setStartedTime(startedTime);
        detail.setFinishedTime(finishedTime);
        if(startedTime != null) {
            LocalDateTime end = finishedTime == null ? LocalDateTime.now() : finishedTime;
            detail.setDurationMs(Math.max(0,Duration.between(startedTime,end).toMillis()));
        }
        return detail;
    }

    private String summary(String status, Integer successCount, Integer failedCount, String errorMessage) {
        int successes = value(successCount);
        int failures = value(failedCount);
        if("SUCCESS".equalsIgnoreCase(status)) return "成功处理 " + successes + " 项，失败 0 项";
        if("PARTIAL_SUCCESS".equalsIgnoreCase(status)) return "成功处理 " + successes + " 项，失败 " + failures + " 项";
        if("FAILED".equalsIgnoreCase(status)) return failures > 0 ? "任务失败，共 " + failures + " 项未完成" : "任务执行失败";
        int total = successes + failures;
        return total > 0 ? "已处理 " + total + " 项" : firstNonBlank(errorMessage,status);
    }

    private String safeError(String value) {
        if(value == null || value.isBlank()) return null;
        String sanitized = value
                .replaceAll("(?i)(bearer\\s+)[a-z0-9._~+\\-/]+=*","$1[REDACTED]")
                .replaceAll("(?i)(api[-_ ]?key|token|password|secret)(\\s*[:=]\\s*)[^\\s,;]+","$1$2[REDACTED]")
                .replaceAll("(?i)https?://[^\\s?]+\\?[^\\s]+","[REDACTED_URL]");
        return sanitized.length() <= 1000 ? sanitized : sanitized.substring(0,1000);
    }

    private AsyncTaskVO base(String source, Long taskId, Long spaceId, Long documentId, String type,
                             String title, String status, String errorMessage,
                             LocalDateTime createTime, LocalDateTime updateTime) {
        AsyncTaskVO vo = new AsyncTaskVO();
        vo.setId(source + "-" + taskId);
        vo.setTaskId(taskId);
        vo.setSpaceId(spaceId);
        vo.setDocumentId(documentId);
        vo.setSource(source);
        vo.setType(type);
        vo.setTitle(title);
        vo.setStatus(status);
        vo.setErrorMessage(errorMessage);
        vo.setCreateTime(createTime);
        vo.setUpdateTime(updateTime);
        return vo;
    }

    private String ragTitle(String type) {
        if("REBUILD_SPACE".equals(type)) return "Space RAG 重建";
        if("REPAIR_SPACE".equals(type)) return "Space 向量修复";
        if("REBUILD_FILE".equals(type)) return "文件 RAG 重建";
        return "文件 RAG 索引";
    }

    private String ragMessage(SpaceRagTask task, int current, int total) {
        if(task.getErrorMessage() != null && !task.getErrorMessage().isBlank()) return safeError(task.getErrorMessage());
        return total > 0 ? current + "/" + total : task.getTaskStatus();
    }

    private int progress(String status, int current, int total) {
        if("SUCCESS".equalsIgnoreCase(status)) return 100;
        if(total <= 0) return "RUNNING".equalsIgnoreCase(status) ? 1 : 0;
        return Math.max(0,Math.min(100,(int) Math.round(current * 100.0 / total)));
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }

    private String firstNonBlank(String... values) {
        for(String value : values) {
            if(value != null && !value.isBlank()) return value;
        }
        return "PENDING";
    }
}
