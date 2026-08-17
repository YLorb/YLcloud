package com.ylcloud.service;

import com.ylcloud.DTO.SpaceFileImportBatchCreateDTO;
import com.ylcloud.DTO.SpaceFolderCreateDTO;
import com.ylcloud.DTO.UserFileDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.Exception.NotFoundException;
import com.ylcloud.VO.SpaceFileImportBatchVO;
import com.ylcloud.VO.SpaceFileImportItemVO;
import com.ylcloud.VO.SpaceFileVO;
import com.ylcloud.async.task.DomainTaskPayload;
import com.ylcloud.async.task.TaskCreateCommand;
import com.ylcloud.async.task.UnifiedTaskCenterService;
import com.ylcloud.entity.File;
import com.ylcloud.entity.SpaceFileImportBatch;
import com.ylcloud.entity.SpaceFileImportItem;
import com.ylcloud.entity.UnifiedAsyncTask;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.SpaceFileImportBatchMapper;
import com.ylcloud.utils.MinioclientUtil;
import com.ylcloud.utils.UuidUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class SpaceFileImportBatchService {
    private final SpaceFileImportBatchMapper mapper;
    private final FileInfoMapper fileMapper;
    private final SpaceFileService spaceFiles;
    private final SpaceFileAccessService access;
    private final SpaceFilePreflightService preflight;
    private final MinioclientUtil minio;
    private final TransactionTemplate transactions;
    private final UnifiedTaskCenterService taskCenter;

    public SpaceFileImportBatchService(SpaceFileImportBatchMapper mapper, FileInfoMapper fileMapper,
                                       SpaceFileService spaceFiles, SpaceFileAccessService access,
                                       SpaceFilePreflightService preflight, MinioclientUtil minio,
                                       TransactionTemplate transactions, UnifiedTaskCenterService taskCenter) {
        this.mapper = mapper;
        this.fileMapper = fileMapper;
        this.spaceFiles = spaceFiles;
        this.access = access;
        this.preflight = preflight;
        this.minio = minio;
        this.transactions = transactions;
        this.taskCenter = taskCenter;
    }

    /** Freeze the Personal tree into immutable import items; activation happens asynchronously. */
    @Transactional
    public SpaceFileImportBatchVO create(Long spaceId, SpaceFileImportBatchCreateDTO dto, Long userId) {
        access.requireCreate(spaceId, userId);
        Long target = dto.getTargetParentId();
        if (target == null || target == 0) target = spaceFiles.tree(spaceId, userId).get(0).getId();
        List<Snapshot> snapshots = new ArrayList<>();
        for (Long sourceId : new LinkedHashSet<>(dto.getSourceNodeIds())) snapshot(sourceId, userId, "", 0, snapshots);
        if (snapshots.stream().mapToInt(Snapshot::depth).max().orElse(0) + 1 > 100) {
            throw new BaseException("导入目录超过 100 层");
        }

        LocalDateTime now = LocalDateTime.now();
        SpaceFileImportBatch batch = new SpaceFileImportBatch();
        batch.setBatchKey("space-import:" + spaceId + ":" + userId + ":" + UuidUtil.randomUuid());
        batch.setSpaceId(spaceId);
        batch.setTargetParentId(target);
        batch.setFailurePolicy(dto.getFailurePolicy());
        batch.setBatchStatus("PENDING");
        batch.setTotalCount(snapshots.size());
        batch.setCreatedBy(userId);
        batch.setCreatetime(now);
        batch.setUpdatetime(now);
        mapper.insertBatch(batch);

        for (Snapshot snapshot : snapshots) {
            SpaceFileImportItem item = new SpaceFileImportItem();
            item.setBatchId(batch.getId());
            item.setSourceUserFileId(snapshot.file().getId());
            item.setSourceType(snapshot.file().getDir() == 1 ? "DIRECTORY" : "FILE");
            item.setRelativePath(snapshot.relativePath());
            item.setFileUuid(snapshot.file().getFileUuid());
            item.setItemStatus("PENDING_PREFLIGHT");
            if (snapshot.file().getDir() == 0) {
                File physical = fileMapper.getFileByFileUuid(snapshot.file().getFileUuid(), userId);
                if (physical == null) throw new NotFoundException("Personal 物理文件不存在");
                item.setContentHash(physical.getHash());
                item.setFileSize(physical.getSize());
            }
            item.setCreatetime(now);
            item.setUpdatetime(now);
            mapper.insertItem(item);
        }

        UnifiedAsyncTask task = taskCenter.createTask(new TaskCreateCommand(
                batch.getBatchKey(), "maintenance", "SPACE_FILE_IMPORT", new DomainTaskPayload(batch.getId()),
                userId, spaceId, "space-import-batch:" + batch.getId(), 1L, null, 5));
        mapper.bindTask(batch.getId(), task.getId(), now);
        return get(spaceId, batch.getId(), userId);
    }

    public SpaceFileImportBatchVO get(Long spaceId, Long batchId, Long userId) {
        access.requireRead(spaceId, userId);
        SpaceFileImportBatch batch = requireBatch(batchId);
        if (!spaceId.equals(batch.getSpaceId())) throw new NotFoundException("导入批次不存在");
        SpaceFileImportBatchVO vo = new SpaceFileImportBatchVO();
        vo.setId(batch.getId()); vo.setSpaceId(batch.getSpaceId()); vo.setTargetParentId(batch.getTargetParentId());
        vo.setFailurePolicy(batch.getFailurePolicy()); vo.setBatchStatus(batch.getBatchStatus());
        vo.setTotalCount(batch.getTotalCount()); vo.setPassedCount(batch.getPassedCount());
        vo.setFailedCount(batch.getFailedCount()); vo.setImportedCount(batch.getImportedCount());
        vo.setErrorMessage(batch.getErrorMessage()); vo.setAsyncTaskId(batch.getAsyncTaskId());
        vo.setItems(mapper.listItems(batchId).stream().map(this::toItemVO).toList());
        return vo;
    }

    /** Unified task entry point. Imported items are checkpoints and are never activated twice. */
    public Map<String, Object> executeBatch(Long batchId) {
        SpaceFileImportBatch batch = requireBatch(batchId);
        if ("SUCCESS".equals(batch.getBatchStatus()) || "PARTIAL_SUCCESS".equals(batch.getBatchStatus())) return result(batch);
        List<SpaceFileImportItem> items = mapper.listItems(batchId);
        int passed = 0, failed = 0, imported = 0;
        for (SpaceFileImportItem item : items) {
            if ("IMPORTED".equals(item.getItemStatus())) { passed++; imported++; continue; }
            if ("DIRECTORY".equals(item.getSourceType())) {
                mark(item, "PREFLIGHT_PASSED", null, null, null, null); passed++; continue;
            }
            try {
                File physical = fileMapper.getFileByFileUuid(item.getFileUuid(), batch.getCreatedBy());
                if (physical == null || !Objects.equals(item.getContentHash(), physical.getHash())
                        || !Objects.equals(item.getFileSize(), physical.getSize())) {
                    throw new BaseException("Personal 来源文件在批次创建后已变化");
                }
                spaceFiles.requireNoDuplicateContent(batch.getSpaceId(), item.getContentHash());
                String invocation = preflight.inspect(fileName(item.getRelativePath()), item.getContentHash(),
                        item.getFileSize(), minio.getObjectStream(item.getFileUuid()), batch.getCreatedBy());
                mark(item, "PREFLIGHT_PASSED", null, null, invocation, null); passed++;
            } catch (Exception ex) {
                mark(item, "PREFLIGHT_FAILED", "SANDBOX_REJECTED", safe(ex.getMessage()), null, null); failed++;
            }
        }
        if (failed > 0 && "ATOMIC".equals(batch.getFailurePolicy())) {
            mapper.finishBatch(batchId, "FAILED", passed, failed, imported,
                    "Sandbox 预检失败，整批未导入", LocalDateTime.now());
            return result(requireBatch(batchId));
        }

        int alreadyImported = imported;
        try {
            int newlyImported = "ATOMIC".equals(batch.getFailurePolicy())
                    ? Objects.requireNonNull(transactions.execute(status -> importPassed(batch, items, true)))
                    : importPassed(batch, items, false);
            imported += newlyImported;
        } catch (RuntimeException ex) {
            mapper.finishBatch(batchId, "FAILED", passed, countFailed(batchId), alreadyImported,
                    safe(ex.getMessage()), LocalDateTime.now());
            throw ex;
        }
        failed = countFailed(batchId);
        String status = failed == 0 ? "SUCCESS" : imported > 0 ? "PARTIAL_SUCCESS" : "FAILED";
        mapper.finishBatch(batchId, status, passed, failed, imported, null, LocalDateTime.now());
        return result(requireBatch(batchId));
    }

    private int importPassed(SpaceFileImportBatch batch, List<SpaceFileImportItem> items, boolean atomic) {
        Map<String, Long> folders = new HashMap<>();
        folders.put("", batch.getTargetParentId());
        int imported = 0;
        for (SpaceFileImportItem item : items) {
            if ("IMPORTED".equals(item.getItemStatus())) {
                if ("DIRECTORY".equals(item.getSourceType())) folders.put(item.getRelativePath(), item.getSpaceFileId());
                continue;
            }
            if (!"PREFLIGHT_PASSED".equals(item.getItemStatus())) continue;
            try {
                Long parentId = folders.get(parentPath(item.getRelativePath()));
                if (parentId == null) throw new BaseException("父目录导入失败");
                SpaceFileVO created;
                if ("DIRECTORY".equals(item.getSourceType())) {
                    SpaceFolderCreateDTO folder = new SpaceFolderCreateDTO();
                    folder.setName(fileName(item.getRelativePath())); folder.setParentId(parentId);
                    created = atomic ? spaceFiles.createFolder(batch.getSpaceId(), folder, batch.getCreatedBy())
                            : transactions.execute(status -> spaceFiles.createFolder(batch.getSpaceId(), folder, batch.getCreatedBy()));
                    folders.put(item.getRelativePath(), created.getId());
                } else {
                    created = atomic ? spaceFiles.importPreflightedReference(batch.getSpaceId(), parentId,
                            item.getFileUuid(), item.getContentHash(), fileName(item.getRelativePath()), batch.getCreatedBy())
                            : transactions.execute(status -> spaceFiles.importPreflightedReference(batch.getSpaceId(), parentId,
                            item.getFileUuid(), item.getContentHash(), fileName(item.getRelativePath()), batch.getCreatedBy()));
                }
                mark(item, "IMPORTED", null, null, item.getSandboxInvocationId(), created.getId()); imported++;
            } catch (RuntimeException ex) {
                mark(item, "IMPORT_FAILED", "IMPORT_FAILED", safe(ex.getMessage()), item.getSandboxInvocationId(), null);
                if (atomic) throw ex;
            }
        }
        return imported;
    }

    private void snapshot(Long id, Long userId, String parent, int depth, List<Snapshot> out) {
        if (depth > 100) throw new BaseException("Personal 目录超过 100 层");
        UserFileDTO file = fileMapper.getByFileId(id, userId);
        if (file == null) throw new NotFoundException("Personal 文件不存在");
        String path = parent.isEmpty() ? file.getFileName() : parent + "/" + file.getFileName();
        out.add(new Snapshot(file, path, depth));
        if (file.getDir() == 1) for (UserFileDTO child : fileMapper.getUserFileList(file.getId(), userId)) {
            snapshot(child.getId(), userId, path, depth + 1, out);
        }
    }

    private SpaceFileImportBatch requireBatch(Long id) {
        SpaceFileImportBatch batch = mapper.getBatch(id);
        if (batch == null) throw new NotFoundException("导入批次不存在");
        return batch;
    }
    private int countFailed(Long id) {
        return (int) mapper.listItems(id).stream().filter(item -> item.getItemStatus().endsWith("FAILED")).count();
    }
    private Map<String, Object> result(SpaceFileImportBatch batch) {
        return Map.of("batchId", batch.getId(), "status", batch.getBatchStatus(),
                "imported", batch.getImportedCount(), "failed", batch.getFailedCount());
    }
    private String parentPath(String path) { int at = path.lastIndexOf('/'); return at < 0 ? "" : path.substring(0, at); }
    private String fileName(String path) { int at = path.lastIndexOf('/'); return at < 0 ? path : path.substring(at + 1); }
    private String safe(String message) { return message == null ? "导入失败" : message.substring(0, Math.min(message.length(), 500)); }

    private void mark(SpaceFileImportItem item, String status, String code, String message,
                      String invocation, Long fileId) {
        item.setItemStatus(status); item.setErrorCode(code); item.setErrorMessage(message);
        if (invocation != null) item.setSandboxInvocationId(invocation);
        if (fileId != null) item.setSpaceFileId(fileId);
        mapper.updateItem(item.getId(), status, code, message, fileId,
                invocation == null ? item.getSandboxInvocationId() : invocation, LocalDateTime.now());
    }
    private SpaceFileImportItemVO toItemVO(SpaceFileImportItem item) {
        SpaceFileImportItemVO vo = new SpaceFileImportItemVO();
        vo.setId(item.getId()); vo.setSourceUserFileId(item.getSourceUserFileId());
        vo.setRelativePath(item.getRelativePath()); vo.setItemStatus(item.getItemStatus());
        vo.setErrorCode(item.getErrorCode()); vo.setErrorMessage(item.getErrorMessage());
        vo.setSpaceFileId(item.getSpaceFileId()); vo.setSandboxInvocationId(item.getSandboxInvocationId());
        return vo;
    }
    private record Snapshot(UserFileDTO file, String relativePath, int depth) { }
}
