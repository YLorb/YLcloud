package com.ylcloud.service;

import com.ylcloud.DTO.FileDTO;
import com.ylcloud.DTO.SpaceFileImportDTO;
import com.ylcloud.DTO.SpaceFileMoveDTO;
import com.ylcloud.DTO.SpaceFileRenameDTO;
import com.ylcloud.DTO.SpaceFileDeleteConfirmDTO;
import com.ylcloud.DTO.SpaceFolderCreateDTO;
import com.ylcloud.DTO.SpaceWebLinkImportDTO;
import com.ylcloud.DTO.UserFileDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.Exception.ConflictException;
import com.ylcloud.Exception.NotFoundException;
import com.ylcloud.VO.FilePreviewVO;
import com.ylcloud.VO.SpaceFileVO;
import com.ylcloud.VO.SpaceFileDeletePreviewVO;
import com.ylcloud.VO.SpaceFileDeleteTaskVO;
import com.ylcloud.authorization.SpaceFileAction;
import com.ylcloud.constant.StatusConstant;
import com.ylcloud.entity.File;
import com.ylcloud.entity.Space;
import com.ylcloud.entity.SpaceFile;
import com.ylcloud.entity.SpaceFileDeleteBatch;
import com.ylcloud.entity.UnifiedAsyncTask;
import com.ylcloud.async.task.DomainTaskPayload;
import com.ylcloud.async.task.TaskCreateCommand;
import com.ylcloud.async.task.UnifiedTaskCenterService;
import com.ylcloud.entity.CrossStoreOperation;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.SpaceFileMapper;
import com.ylcloud.mapper.SpaceFileContentGuardMapper;
import com.ylcloud.mapper.SpaceFileDeleteBatchMapper;
import com.ylcloud.utils.HashUtil;
import com.ylcloud.utils.Md5Util;
import com.ylcloud.utils.MinioclientUtil;
import com.ylcloud.utils.UuidUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 空间文件树业务服务。
 */
@Service
public class SpaceFileService {
    private static final long MAX_TEXT_PREVIEW_SIZE = 1024 * 1024;
    private static final Pattern TITLE_PATTERN = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");

    private final SpaceFileMapper spaceFileMapper;
    private final SpaceFileContentGuardMapper contentGuardMapper;
    private final SpaceFileDeleteBatchMapper deleteBatchMapper;
    private final UnifiedTaskCenterService taskCenter;
    private final TransactionTemplate transactionTemplate;
    private final FileInfoMapper fileInfoMapper;
    private final SpaceService spaceService;
    private final SpacePermissionService spacePermissionService;
    private final SpaceFileAccessService spaceFileAccessService;
    private final SpaceFilePreflightService preflightService;
    private final SpaceRagService spaceRagService;
    private final MinioclientUtil minioclientUtil;
    private final SiteSettingService siteSettingService;
    private final PhysicalFileCleanupService physicalFileCleanupService;
    private final InitialFileVersionService initialFileVersionService;
    private final CrossStoreFileWriteService crossStoreFileWriteService;
    private final CrossStoreOperationService crossStoreOperationService;
    private final SpaceFileLifecycleService lifecycleService;
    private QuotaService quotaService;

    @Value("${ylcloud.upload.max-file-size:2147483648}")
    private Long maxFileSize;

    @Value("${ylcloud.rag.web-link.max-size:5242880}")
    private Long maxWebLinkSize;

    /**
     * 初始化 SpaceFileService 对象。
     *
     * @param spaceFileMapper 方法入参
     * @param fileInfoMapper 方法入参
     * @param spaceService 空间服务
     * @param spacePermissionService 方法入参
     * @param spaceRagService 空间 RAG 服务
     * @param minioclientUtil 方法入参
     */
    public SpaceFileService(SpaceFileMapper spaceFileMapper,
                            SpaceFileContentGuardMapper contentGuardMapper,
                            SpaceFileDeleteBatchMapper deleteBatchMapper,
                            UnifiedTaskCenterService taskCenter,
                            TransactionTemplate transactionTemplate,
                            FileInfoMapper fileInfoMapper,
                            SpaceService spaceService,
                            SpacePermissionService spacePermissionService,
                            SpaceFileAccessService spaceFileAccessService,
                            SpaceFilePreflightService preflightService,
                            SpaceRagService spaceRagService,
                            MinioclientUtil minioclientUtil,
                            SiteSettingService siteSettingService,
                            PhysicalFileCleanupService physicalFileCleanupService,
                            InitialFileVersionService initialFileVersionService,
                            CrossStoreFileWriteService crossStoreFileWriteService,
                            CrossStoreOperationService crossStoreOperationService,
                            SpaceFileLifecycleService lifecycleService) {
        this.spaceFileMapper = spaceFileMapper;
        this.contentGuardMapper = contentGuardMapper;
        this.deleteBatchMapper = deleteBatchMapper;
        this.taskCenter = taskCenter;
        this.transactionTemplate = transactionTemplate;
        this.fileInfoMapper = fileInfoMapper;
        this.spaceService = spaceService;
        this.spacePermissionService = spacePermissionService;
        this.spaceFileAccessService = spaceFileAccessService;
        this.preflightService = preflightService;
        this.spaceRagService = spaceRagService;
        this.minioclientUtil = minioclientUtil;
        this.siteSettingService = siteSettingService;
        this.physicalFileCleanupService = physicalFileCleanupService;
        this.initialFileVersionService = initialFileVersionService;
        this.crossStoreFileWriteService = crossStoreFileWriteService;
        this.crossStoreOperationService = crossStoreOperationService;
        this.lifecycleService = lifecycleService;
    }

    @Autowired(required=false)
    public void setQuotaService(QuotaService quotaService) { this.quotaService=quotaService; }

    /**
     * 查询 listFiles 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param parentId 父级 ID
     * @param userId 用户 ID
     * @return 列表结果
     */
    public List<SpaceFileVO> listFiles(Long spaceId, Long parentId, Long userId) {
        spaceFileAccessService.requireRead(spaceId,userId);
        Long realParentId = normalizeParentId(spaceId,parentId);
        return toVOList(spaceFileMapper.listByParentId(spaceId,realParentId),userId);
    }

    /**
     * 执行 tree 函数的业务处理。
     *
     * @param spaceId 空间 ID
     * @param userId 用户 ID
     * @return 列表结果
     */
    public List<SpaceFileVO> tree(Long spaceId, Long userId) {
        spaceFileAccessService.requireRead(spaceId,userId);
        Long rootId = normalizeParentId(spaceId,null);
        SpaceFile root = spaceFileMapper.getById(spaceId,rootId);
        SpaceFileVO rootVO = toVO(root,userId);
        rootVO.setChildren(buildChildren(spaceId,rootId,userId));
        return List.of(rootVO);
    }

    /**
     * 创建 createFolder 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param dto 请求参数
     * @param userId 用户 ID
     * @return 处理结果
     */
    @Transactional
    public SpaceFileVO createFolder(Long spaceId, SpaceFolderCreateDTO dto, Long userId) {
        spaceFileAccessService.requireCreate(spaceId,userId);
        String folderName = requireSafeFileName(dto.getName());
        Long parentId = normalizeParentId(spaceId,dto.getParentId());
        SpaceFile parent = requireDirectory(spaceId,parentId);
        spaceFileMapper.lockById(spaceId,parentId);
        requireNoSameName(spaceId,parentId,folderName,1);

        LocalDateTime now = LocalDateTime.now();
        SpaceFile folder = new SpaceFile();
        folder.setSpaceId(spaceId);
        folder.setFileName(folderName);
        folder.setDir(1);
        folder.setParentId(parentId);
        folder.setPath(buildPath(parent,folderName,true));
        requireDepth(parent.getDepth() + 1);
        folder.setDepth(parent.getDepth() + 1);
        folder.setNodeVersion(1L);
        folder.setLifecycleState("ACTIVE");
        folder.setStatus(StatusConstant.ENABLE);
        folder.setCreatedBy(userId);
        folder.setCreatetime(now);
        folder.setUpdatetime(now);
        spaceFileMapper.insert(folder);
        return toVO(folder,userId);
    }

    /**
     * 执行 importUserFile 函数的业务处理。
     *
     * @param spaceId 空间 ID
     * @param dto 请求参数
     * @param userId 用户 ID
     * @return 处理结果
     */
    @Transactional
    public SpaceFileVO importUserFile(Long spaceId, SpaceFileImportDTO dto, Long userId) {
        spaceFileAccessService.requireCreate(spaceId,userId);
        Long parentId = normalizeParentId(spaceId,dto.getParentId());
        SpaceFile parent = requireDirectory(spaceId,parentId);
        spaceFileMapper.lockById(spaceId,parentId);
        UserFileDTO userFile = fileInfoMapper.getByFileId(dto.getUserFileId(),userId);
        if(userFile == null || userFile.getDir() == 1) {
            throw new BaseException("只能导入当前用户可读取的文件");
        }
        String fileName = dto.getName() == null || dto.getName().isBlank() ? userFile.getFileName() : dto.getName();
        fileName = requireSafeFileName(fileName);
        requireNoSameName(spaceId,parentId,fileName,0);
        File physical = fileInfoMapper.getFileByFileUuid(userFile.getFileUuid(),userId);
        if(physical == null) throw new BaseException("物理文件不存在");
        requireNoDuplicateContent(spaceId,physical.getHash());
        try {
            preflightService.inspect(fileName,physical.getHash(),physical.getSize(),minioclientUtil.getObjectStream(physical.getFileUuid()),userId);
        } catch(BaseException ex) { throw ex; }
        catch(Exception ex) { throw new BaseException(503,"无法读取文件进行 Sandbox 预检"); }
        if(quotaService != null) quotaService.requireTeamStorage(spaceId,quotaKey(physical),physical.getSize());

        LocalDateTime now = LocalDateTime.now();
        SpaceFile spaceFile = new SpaceFile();
        spaceFile.setSpaceId(spaceId);
        spaceFile.setFileUuid(userFile.getFileUuid());
        spaceFile.setFileName(fileName);
        spaceFile.setDir(0);
        spaceFile.setParentId(parentId);
        spaceFile.setPath(buildPath(parent,fileName,false));
        requireDepth(parent.getDepth() + 1);
        spaceFile.setDepth(parent.getDepth() + 1);
        spaceFile.setNodeVersion(1L);
        spaceFile.setContentHash(physical.getHash());
        spaceFile.setLifecycleState("ACTIVE");
        reserveContent(spaceId,physical.getHash());
        spaceFile.setStatus(StatusConstant.ENABLE);
        spaceFile.setCreatedBy(userId);
        spaceFile.setCreatetime(now);
        spaceFile.setUpdatetime(now);
        spaceFileMapper.insert(spaceFile);
        activateContentGuard(spaceFile);
        if(quotaService != null) quotaService.recordTeamFile(spaceFile.getId(),spaceId,spaceFile.getFileUuid());
        if(fileInfoMapper.updateFileCount(userFile.getFileUuid(),1) == 0) {
            throw new BaseException("文件引用计数更新失败");
        }
        ensureInitialVersionIfEnabled(spaceFile,userId);
        lifecycleService.fileAdded(spaceFile,userId);
        spaceRagService.handleFileImported(spaceFile,userId);
        return toVO(spaceFileMapper.getById(spaceId,spaceFile.getId()),userId);
    }

    /**
     * 上传本地文件到空间。
     *
     * @param spaceId 空间 ID
     * @param uploadFile 上传文件
     * @param parentId 父级目录 ID
     * @param name 可选覆盖文件名
     * @param userId 用户 ID
     * @return 处理结果
     */
    @Transactional
    public SpaceFileVO uploadFile(Long spaceId, MultipartFile uploadFile, Long parentId, String name, Long userId) {
        return uploadFile(spaceId,uploadFile,parentId,name,userId,null);
    }

    @Transactional
    public SpaceFileVO uploadFile(Long spaceId,
                                  MultipartFile uploadFile,
                                  Long parentId,
                                  String name,
                                  Long userId,
                                  String idempotencyKey) {
        spaceFileAccessService.requireCreate(spaceId,userId);
        validateUploadFile(uploadFile);
        String fileName = name == null || name.isBlank() ? uploadFile.getOriginalFilename() : name;
        fileName = requireSafeFileName(fileName);
        Long realParentId = normalizeParentId(spaceId,parentId);
        SpaceFile parent = requireDirectory(spaceId,realParentId);
        spaceFileMapper.lockById(spaceId,realParentId);
        FileFingerprint fingerprint = calculateFingerprint(uploadFile);
        requireNoDuplicateContent(spaceId,fingerprint.hash());
        try {
            preflightService.inspect(fileName,fingerprint.hash(),uploadFile.getSize(),uploadFile.getInputStream(),userId);
        } catch(BaseException ex) { throw ex; }
        catch(Exception ex) { throw new BaseException(503,"无法读取上传文件进行 Sandbox 预检"); }
        if(quotaService != null) quotaService.requireTeamStorage(spaceId,fingerprint.hash(),uploadFile.getSize());
        CrossStoreOperation operation;
        AtomicReference<String> resultRef = new AtomicReference<>();
        String effectiveKey = idempotencyKey == null || idempotencyKey.isBlank() ? UuidUtil.randomUuid() : idempotencyKey;
        requireValidIdempotencyKey(effectiveKey);
        String operationKey = CrossStoreOperationService.key("SPACE_UPLOAD",userId,effectiveKey);
        operation = crossStoreOperationService.claim(
                operationKey,
                "SPACE_UPLOAD",
                CrossStoreOperationService.payloadHash(spaceId,realParentId,fileName,uploadFile.getSize(),fingerprint.hash()),
                UuidUtil.randomUuid());
        if(CrossStoreOperationService.SUCCESS.equals(operation.getOperationStatus())) {
            return replaySpaceUpload(operation,spaceId,realParentId);
        }
        crossStoreOperationService.completeAfterCommit(operationKey,resultRef::get);
        requireNoSameName(spaceId,realParentId,fileName,0);

        StoredPhysicalFile stored = storeMultipartFile(uploadFile,fileName,fingerprint,
                operation.getResourceId());
        SpaceFile spaceFile = createSpaceFile(spaceId,parent,stored.fileUuid(),fileName,userId);
        ensureInitialVersionIfEnabled(spaceFile,userId);
        lifecycleService.fileAdded(spaceFile,userId);
        spaceRagService.handleFileImported(spaceFile,userId);
        resultRef.set(String.valueOf(spaceFile.getId()));
        crossStoreOperationService.recordResultCandidate(operationKey,resultRef.get());
        return toVO(spaceFileMapper.getById(spaceId,spaceFile.getId()),userId);
    }

    /**
     * 抓取网页链接并作为 Markdown 文档导入空间。
     *
     * @param spaceId 空间 ID
     * @param dto 请求参数
     * @param userId 用户 ID
     * @return 处理结果
     */
    @Transactional
    public SpaceFileVO importWebLink(Long spaceId, SpaceWebLinkImportDTO dto, Long userId) {
        spaceFileAccessService.requireCreate(spaceId,userId);
        URI uri = requireHttpUri(dto.getUrl());
        WebPageSnapshot snapshot = fetchWebPage(uri);
        String fileName = dto.getName() == null || dto.getName().isBlank() ? defaultLinkFileName(snapshot.title(),uri) : dto.getName();
        fileName = requireSafeFileName(ensureMarkdownExtension(fileName));
        Long parentId = normalizeParentId(spaceId,dto.getParentId());
        SpaceFile parent = requireDirectory(spaceId,parentId);
        spaceFileMapper.lockById(spaceId,parentId);
        requireNoSameName(spaceId,parentId,fileName,0);

        String markdown = buildWebLinkMarkdown(uri,snapshot);
        byte[] content = markdown.getBytes(StandardCharsets.UTF_8);
        requireNoDuplicateContent(spaceId,HashUtil.sha256(content));
        preflightService.inspect(fileName,HashUtil.sha256(content),content.length,new ByteArrayInputStream(content),userId);
        if(quotaService != null) quotaService.requireTeamStorage(spaceId,HashUtil.sha256(content),content.length);
        String operationKey = CrossStoreOperationService.key("SPACE_GENERATED",userId,UuidUtil.randomUuid());
        AtomicReference<String> resultRef = new AtomicReference<>();
        CrossStoreOperation operation = crossStoreOperationService.claim(
                operationKey,"SPACE_GENERATED",
                CrossStoreOperationService.payloadHash(spaceId,parentId,fileName,HashUtil.sha256(content)),
                UuidUtil.randomUuid());
        crossStoreOperationService.completeAfterCommit(operationKey,resultRef::get);
        StoredPhysicalFile stored = storeGeneratedFile(
                fileName,content,"text/markdown;charset=UTF-8",operation.getResourceId());
        SpaceFile spaceFile = createSpaceFile(spaceId,parent,stored.fileUuid(),fileName,userId);
        ensureInitialVersionIfEnabled(spaceFile,userId);
        lifecycleService.fileAdded(spaceFile,userId);
        spaceRagService.handleFileImported(spaceFile,userId);
        resultRef.set(String.valueOf(spaceFile.getId()));
        crossStoreOperationService.recordResultCandidate(operationKey,resultRef.get());
        return toVO(spaceFileMapper.getById(spaceId,spaceFile.getId()),userId);
    }

    /** Activates an immutable, already-preflighted Personal snapshot in a Space. */
    @Transactional
    SpaceFileVO importPreflightedReference(Long spaceId,Long parentId,String fileUuid,String expectedHash,
                                           String requestedName,Long userId) {
        spaceFileAccessService.requireCreate(spaceId,userId);
        SpaceFile parent=requireDirectory(spaceId,parentId);
        spaceFileMapper.lockById(spaceId,parentId);
        String fileName=requireSafeFileName(requestedName);
        requireNoSameName(spaceId,parentId,fileName,0);
        File physical=fileInfoMapper.getFileByFileUuid(fileUuid,userId);
        if(physical==null || !java.util.Objects.equals(expectedHash,physical.getHash())) {
            throw new ConflictException("Personal 来源文件在批次创建后已变化");
        }
        if(quotaService!=null) quotaService.requireTeamStorage(spaceId,quotaKey(physical),physical.getSize());
        SpaceFile node=createSpaceFile(spaceId,parent,fileUuid,fileName,userId);
        if(fileInfoMapper.updateFileCount(fileUuid,1)==0) throw new BaseException("文件引用计数更新失败");
        ensureInitialVersionIfEnabled(node,userId);
        lifecycleService.fileAdded(node,userId);
        spaceRagService.handleFileImported(node,userId);
        return toVO(spaceFileMapper.getById(spaceId,node.getId()),userId);
    }

    public List<SpaceFileVO> search(Long spaceId, String query, Integer limit, Long userId) {
        spaceFileAccessService.requireRead(spaceId,userId);
        String normalized = query == null ? "" : query.trim();
        if(normalized.isEmpty()) {
            return List.of();
        }
        int safeLimit = Math.max(1,Math.min(limit == null ? 50 : limit,200));
        return toVOList(spaceFileMapper.search(spaceId,normalized,safeLimit),userId);
    }

    public List<SpaceFileVO> duplicatesReport(Long spaceId, Long userId) {
        spacePermissionService.requireAdmin(spaceId,userId);
        return toVOList(spaceFileMapper.listLegacyDuplicates(spaceId),userId);
    }

    public List<SpaceFileVO> ancestors(Long spaceId, Long folderId, Long userId) {
        spaceFileAccessService.requireRead(spaceId,userId);
        SpaceFile cursor = requireDirectory(spaceId,folderId);
        List<SpaceFileVO> result = new ArrayList<>();
        for(int guard = 0; cursor != null && guard <= 100; guard++) {
            result.add(0,toVO(cursor,userId));
            if(Long.valueOf(0L).equals(cursor.getParentId())) {
                return result;
            }
            cursor = spaceFileMapper.getById(spaceId,cursor.getParentId());
        }
        throw new BaseException("目录祖先链无效或超过 100 层");
    }

    @Transactional
    public SpaceFileVO rename(Long spaceId, Long fileId, SpaceFileRenameDTO dto, Long userId) {
        spaceFileAccessService.requireRead(spaceId,userId);
        spaceFileMapper.lockActiveSpaceNodes(spaceId);
        SpaceFile node = spaceFileAccessService.requireNodeAction(spaceId,fileId,userId,SpaceFileAction.RENAME);
        String name = requireSafeFileName(dto.getName());
        requireNoSameNameExcluding(spaceId,node.getParentId(),name,node.getDir(),node.getId());
        SpaceFile parent = requireDirectory(spaceId,node.getParentId());
        String oldPath = node.getPath();
        String newPath = buildPath(parent,name,node.getDir() == 1);
        LocalDateTime now = LocalDateTime.now();
        if(spaceFileMapper.updateName(spaceId,fileId,name,newPath,dto.getExpectedVersion(),now) != 1) {
            throw new ConflictException("节点已被其他操作修改，请刷新后重试");
        }
        if(node.getDir() == 1) {
            spaceFileMapper.updateDescendantLocations(spaceId,fileId,oldPath,newPath,0,now);
        }
        return toVO(spaceFileMapper.getById(spaceId,fileId),userId);
    }

    @Transactional
    public SpaceFileVO move(Long spaceId, Long fileId, SpaceFileMoveDTO dto, Long userId) {
        spaceFileAccessService.requireRead(spaceId,userId);
        spaceFileMapper.lockActiveSpaceNodes(spaceId);
        SpaceFile node = spaceFileAccessService.requireNodeAction(spaceId,fileId,userId,SpaceFileAction.MOVE);
        SpaceFile target = requireDirectory(spaceId,dto.getTargetParentId());
        if(target.getId().equals(node.getParentId())) {
            if(!dto.getExpectedVersion().equals(node.getNodeVersion())) {
                throw new ConflictException("节点已被其他操作修改，请刷新后重试");
            }
            return toVO(node,userId);
        }
        if(node.getId().equals(target.getId())
                || (node.getDir() == 1 && spaceFileMapper.countInSubtree(spaceId,node.getId(),target.getId()) > 0)) {
            throw new ConflictException("不能把目录移动到自身或其子目录");
        }
        requireNoSameName(spaceId,target.getId(),node.getFileName(),node.getDir());
        int subtreeHeight = spaceFileMapper.maxDepthInSubtree(spaceId,node.getId()) - node.getDepth();
        int newDepth = target.getDepth() + 1;
        requireDepth(newDepth + subtreeHeight);
        String oldPath = node.getPath();
        String newPath = buildPath(target,node.getFileName(),node.getDir() == 1);
        int delta = newDepth - node.getDepth();
        LocalDateTime now = LocalDateTime.now();
        if(spaceFileMapper.move(spaceId,fileId,target.getId(),newPath,newDepth,dto.getExpectedVersion(),now) != 1) {
            throw new ConflictException("节点已被其他操作修改，请刷新后重试");
        }
        if(node.getDir() == 1) {
            spaceFileMapper.updateDescendantLocations(spaceId,fileId,oldPath,newPath,delta,now);
        }
        return toVO(spaceFileMapper.getById(spaceId,fileId),userId);
    }

    /**
     * 移除 removeFile 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param fileId 文件 ID
     * @param userId 用户 ID
     * @return 处理结果
     */
    public SpaceFileDeletePreviewVO deletionPreview(Long spaceId, Long fileId, Long userId) {
        SpaceFile file = spaceFileAccessService.requireNodeAction(spaceId,fileId,userId,SpaceFileAction.DELETE);
        if(file.getParentId() == 0L) {
            throw new BaseException("不能删除空间根目录");
        }
        List<SpaceFile> subtree = spaceFileMapper.listSubtree(spaceId,fileId,file.getPath());
        String digest = subtreeDigest(subtree);
        long expiresAt=Instant.now().plusSeconds(300).getEpochSecond();
        int folders = (int) subtree.stream().filter(node -> node.getDir() == 1).count();
        int files = subtree.size() - folders;
        int knowledge = (int) subtree.stream().filter(node -> node.getDir() == 0
                && (Boolean.TRUE.equals(Integer.valueOf(1).equals(node.getSearchable()))
                || node.getKnowledgeState() != null)).count();
        return SpaceFileDeletePreviewVO.builder().fileId(fileId).name(file.getFileName())
                .nodeVersion(file.getNodeVersion()).folderCount(folders).fileCount(files).knowledgeCount(knowledge)
                .subtreeDigest(digest).expiresAtEpochSecond(expiresAt)
                .confirmationToken(deleteConfirmationToken(spaceId,file,userId,digest,expiresAt)).build();
    }

    @Transactional
    public SpaceFileDeleteTaskVO removeFile(Long spaceId, Long fileId, SpaceFileDeleteConfirmDTO dto, Long userId) {
        spaceFileAccessService.requireRead(spaceId,userId);
        spaceFileMapper.lockActiveSpaceNodes(spaceId);
        SpaceFile file = spaceFileAccessService.requireNodeAction(spaceId,fileId,userId,SpaceFileAction.DELETE);
        if(file.getParentId() == 0L) throw new BaseException("不能删除空间根目录");
        if(!file.getFileName().equals(dto.getConfirmationName()) || !file.getNodeVersion().equals(dto.getExpectedVersion())) {
            throw new ConflictException("删除确认信息已过期，请重新预览");
        }
        List<SpaceFile> subtree = spaceFileMapper.listSubtree(spaceId,fileId,file.getPath());
        String digest = subtreeDigest(subtree);
        if(dto.getExpiresAtEpochSecond() < Instant.now().getEpochSecond()
                || !deleteConfirmationToken(spaceId,file,userId,digest,dto.getExpiresAtEpochSecond()).equals(dto.getConfirmationToken())) {
            throw new ConflictException("目录内容已变化，请重新确认删除范围");
        }
        LocalDateTime now = LocalDateTime.now();
        SpaceFileDeleteBatch batch = new SpaceFileDeleteBatch();
        batch.setBatchKey("space-delete:" + spaceId + ":" + fileId + ":" + digest);
        batch.setSpaceId(spaceId); batch.setRootFileId(fileId); batch.setRootNodeVersion(file.getNodeVersion());
        batch.setSubtreeDigest(digest);
        batch.setFolderCount((int) subtree.stream().filter(node -> node.getDir() == 1).count());
        batch.setFileCount(subtree.size() - batch.getFolderCount());
        batch.setKnowledgeCount((int) subtree.stream().filter(node -> node.getDir() == 0 && node.getKnowledgeState() != null).count());
        batch.setBatchStatus("ISOLATED"); batch.setCreatedBy(userId); batch.setCreatetime(now); batch.setUpdatetime(now);
        deleteBatchMapper.insert(batch);
        if(spaceFileMapper.isolateSubtree(spaceId,fileId,file.getPath(),batch.getId(),now) != subtree.size()) {
            throw new ConflictException("目录内容已变化，请重新确认删除范围");
        }
        UnifiedAsyncTask task = taskCenter.createTask(new TaskCreateCommand(
                batch.getBatchKey(),"cleanup","SPACE_FILE_DELETE",new DomainTaskPayload(batch.getId()),userId,spaceId,
                "space-delete-batch:" + batch.getId(),1L,null,5));
        deleteBatchMapper.bindTask(batch.getId(),task.getId(),now);
        return SpaceFileDeleteTaskVO.builder().batchId(batch.getId()).asyncTaskId(task.getId())
                .status("ISOLATED").cancellable(false).build();
    }

    public java.util.Map<String,Object> executeDeleteBatch(Long batchId) {
        SpaceFileDeleteBatch batch = deleteBatchMapper.getById(batchId);
        if(batch == null) throw new NotFoundException("删除批次不存在");
        List<SpaceFile> nodes = spaceFileMapper.listByDeletionBatch(batchId);
        int processed = 0;
        try {
            for(SpaceFile node : nodes) {
                transactionTemplate.executeWithoutResult(status -> removeIsolatedNode(batch.getSpaceId(),node,batch.getCreatedBy()));
                processed++;
            }
            deleteBatchMapper.finish(batchId,"SUCCESS",processed,null,LocalDateTime.now());
            return java.util.Map.of("batchId",batchId,"processed",processed,"status","SUCCESS");
        } catch(RuntimeException ex) {
            deleteBatchMapper.finish(batchId,"FAILED",processed,ex.getMessage(),LocalDateTime.now());
            throw ex;
        }
    }

    private void removeIsolatedNode(Long spaceId, SpaceFile file, Long userId) {
        if(file.getDir() == 0 && file.getFileUuid() != null) lifecycleService.fileRemovalStarted(file);
        if(spaceFileMapper.disable(spaceId,file.getId(),LocalDateTime.now()) == 0) return;
        if(file.getDir() == 0 && file.getFileUuid() != null) {
            releaseOrReassignContentGuard(file);
            if(quotaService != null) quotaService.releaseReference("SPACE_FILE",file.getId());
            if(fileInfoMapper.updateFileCount(file.getFileUuid(),-1) == 0) throw new BaseException("文件引用计数更新失败");
            spaceRagService.handleFileRemoved(spaceId,file.getId(),userId);
            if(fileInfoMapper.getFileCount(file.getFileUuid()) == 0) physicalFileCleanupService.enqueue(file.getFileUuid());
        }
    }

    private String subtreeDigest(List<SpaceFile> nodes) {
        String value = nodes.stream().sorted(Comparator.comparing(SpaceFile::getId))
                .map(node -> node.getId() + ":" + node.getNodeVersion() + ":" + node.getFileUuid())
                .reduce("",(left,right) -> left + "|" + right);
        return HashUtil.sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private String deleteConfirmationToken(Long spaceId, SpaceFile file, Long userId, String digest, long expiresAt) {
        return HashUtil.sha256((spaceId + ":" + file.getId() + ":" + file.getNodeVersion() + ":"
                + userId + ":" + file.getFileName() + ":" + digest + ":" + expiresAt).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 预览 previewFile 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param fileId 文件 ID
     * @param userId 用户 ID
     * @return 处理结果
     */
    public FilePreviewVO previewFile(Long spaceId, Long fileId, Long userId) {
        SpaceFile spaceFile = requirePreviewableSpaceFile(spaceId,fileId,userId);
        File file = requireFileInfo(spaceFile);
        String contentType = resolveContentType(spaceFile.getFileName(),file.getType());
        String previewType = resolvePreviewType(contentType,spaceFile.getFileName());
        FilePreviewVO vo = new FilePreviewVO();
        vo.setFileUuid(spaceFile.getFileUuid());
        vo.setName(spaceFile.getFileName());
        vo.setPreviewType(previewType);
        vo.setContentType(contentType);
        vo.setSize(file.getSize());
        if("text".equals(previewType)) {
            vo.setTextContent(readTextPreview(file));
            return vo;
        }
        if(isStreamPreviewType(previewType)) {
            vo.setPreviewUrl("/api/space/" + spaceId + "/files/" + fileId + "/preview/stream");
            return vo;
        }
        throw new BaseException("当前文件类型不支持预览");
    }

    /**
     * 预览 previewFileStream 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param fileId 文件 ID
     * @param userId 用户 ID
     * @param response 响应对象
     */
    public void previewFileStream(Long spaceId, Long fileId, Long userId, HttpServletResponse response) {
        SpaceFile spaceFile = requirePreviewableSpaceFile(spaceId,fileId,userId);
        File file = requireFileInfo(spaceFile);
        String contentType = resolveContentType(spaceFile.getFileName(),file.getType());
        String previewType = resolvePreviewType(contentType,spaceFile.getFileName());
        if("text".equals(previewType)) {
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        }
        if(!isStreamPreviewType(previewType) && !"text".equals(previewType)) {
            throw new BaseException("当前文件类型不支持预览");
        }
        try {
            minioclientUtil.previewObject(spaceFile.getFileUuid(),spaceFile.getFileName(),contentType,response);
        } catch (Exception e) {
            throw new RuntimeException("空间文件预览失败",e);
        }
    }

    /**
     * 下载 downloadFile 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param fileId 文件 ID
     * @param userId 用户 ID
     * @param response 响应对象
     */
    public void downloadFile(Long spaceId, Long fileId, Long userId, HttpServletResponse response) {
        SpaceFile spaceFile = requirePreviewableSpaceFile(spaceId,fileId,userId);
        File file = requireFileInfo(spaceFile);
        FileDTO fileDTO = new FileDTO();
        fileDTO.setFileUuid(file.getFileUuid());
        fileDTO.setName(spaceFile.getFileName());
        fileDTO.setType(file.getType());
        fileDTO.setSize(file.getSize());
        fileDTO.setHash(file.getHash());
        fileDTO.setMd5(file.getMd5());
        try {
            minioclientUtil.getObject(fileDTO,response);
        } catch (Exception e) {
            throw new RuntimeException("空间文件下载失败",e);
        }
    }

    /**
     * 规范化 normalizeParentId 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param parentId 父级 ID
     * @return 处理结果
     */
    private Long normalizeParentId(Long spaceId, Long parentId) {
        if(parentId != null && parentId != 0L) {
            return parentId;
        }
        Space space = spaceService.requireSpace(spaceId);
        if(space.getRootDirId() == null) {
            throw new BaseException("空间根目录不存在");
        }
        return space.getRootDirId();
    }

    /**
     * 校验 requireDirectory 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param fileId 文件 ID
     * @return 处理结果
     */
    private SpaceFile requireDirectory(Long spaceId, Long fileId) {
        SpaceFile file = spaceFileMapper.getById(spaceId,fileId);
        if(file == null || file.getDir() != 1) {
            throw new NotFoundException("目标目录不存在");
        }
        return file;
    }

    /**
     * 校验 requirePreviewableSpaceFile 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param fileId 文件 ID
     * @param userId 用户 ID
     * @return 处理结果
     */
    private SpaceFile requirePreviewableSpaceFile(Long spaceId, Long fileId, Long userId) {
        SpaceFile spaceFile = spaceFileAccessService.requireNodeAction(spaceId,fileId,userId,SpaceFileAction.READ);
        if(spaceFile == null || spaceFile.getDir() == 1) {
            throw new NotFoundException("空间文件不存在或不是普通文件");
        }
        if(spaceFile.getFileUuid() == null || spaceFile.getFileUuid().isBlank()) {
            throw new BaseException("空间文件缺少物理文件标识");
        }
        return spaceFile;
    }

    /**
     * 校验 requireFileInfo 相关逻辑。
     *
     * @param spaceFile 空间文件对象
     * @return 处理结果
     */
    private File requireFileInfo(SpaceFile spaceFile) {
        File file = fileInfoMapper.getFileByFileUuid(spaceFile.getFileUuid(),spaceFile.getCreatedBy());
        if(file == null) {
            throw new NotFoundException("文件元数据不存在");
        }
        return file;
    }

    /**
     * 执行 readTextPreview 函数的业务处理。
     *
     * @param file 文件对象
     * @return 处理结果
     */
    private String readTextPreview(File file) {
        if(file.getSize() != null && file.getSize() > MAX_TEXT_PREVIEW_SIZE) {
            throw new BaseException("文本文件过大，不支持直接预览");
        }
        try (InputStream inputStream = minioclientUtil.getObjectStream(file.getFileUuid())) {
            return IOUtils.toString(inputStream, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("文本预览失败",e);
        }
    }

    /**
     * 执行 isStreamPreviewType 函数的业务处理。
     *
     * @param previewType 方法入参
     * @return 处理结果
     */
    private boolean isStreamPreviewType(String previewType) {
        return "image".equals(previewType) || "pdf".equals(previewType) || "video".equals(previewType) || "audio".equals(previewType);
    }

    /**
     * 解析 resolvePreviewType 相关逻辑。
     *
     * @param contentType 方法入参
     * @param fileName 文件名
     * @return 处理结果
     */
    private String resolvePreviewType(String contentType, String fileName) {
        if(contentType.startsWith("image/")) return "image";
        if("application/pdf".equals(contentType)) return "pdf";
        if(contentType.startsWith("video/")) return "video";
        if(contentType.startsWith("audio/")) return "audio";
        if(contentType.startsWith("text/") || hasExtension(fileName,".md",".json",".xml",".csv",".log",".java",".js",".ts",".html",".css",".sql",".yml",".yaml")) {
            return "text";
        }
        return "unsupported";
    }

    /**
     * 解析 resolveContentType 相关逻辑。
     *
     * @param fileName 文件名
     * @param storedType 方法入参
     * @return 处理结果
     */
    private String resolveContentType(String fileName, String storedType) {
        String lowerName = fileName == null ? "" : fileName.toLowerCase();
        String lowerType = storedType == null ? "" : storedType.toLowerCase();
        String key = lowerName.isEmpty() ? lowerType : lowerName;
        if(hasExtension(key,".jpg",".jpeg")) return "image/jpeg";
        if(hasExtension(key,".png")) return "image/png";
        if(hasExtension(key,".gif")) return "image/gif";
        if(hasExtension(key,".webp")) return "image/webp";
        if(hasExtension(key,".bmp")) return "image/bmp";
        if(hasExtension(key,".svg")) return "image/svg+xml";
        if(hasExtension(key,".pdf")) return "application/pdf";
        if(hasExtension(key,".mp4")) return "video/mp4";
        if(hasExtension(key,".webm")) return "video/webm";
        if(hasExtension(key,".ogg",".ogv")) return "video/ogg";
        if(hasExtension(key,".mp3")) return "audio/mpeg";
        if(hasExtension(key,".wav")) return "audio/wav";
        if(hasExtension(key,".m4a")) return "audio/mp4";
        if(hasExtension(key,".flac")) return "audio/flac";
        if(hasExtension(key,".txt",".md",".log",".csv",".java",".js",".ts",".html",".css",".sql",".yml",".yaml")) return "text/plain;charset=UTF-8";
        if(hasExtension(key,".json")) return "application/json;charset=UTF-8";
        if(hasExtension(key,".xml")) return "application/xml;charset=UTF-8";
        return "application/octet-stream";
    }

    /**
     * 判断 hasExtension 相关逻辑。
     *
     * @param fileName 文件名
     * @param extensions 方法入参
     * @return 处理结果
     */
    private boolean hasExtension(String fileName, String... extensions) {
        if(fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase();
        for(String extension : extensions) {
            if(lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 校验 requireNoSameName 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param parentId 父级 ID
     * @param fileName 文件名
     * @param dir 方法入参
     */
    private void requireNoSameName(Long spaceId, Long parentId, String fileName, Integer dir) {
        if(spaceFileMapper.countSameName(spaceId,parentId,fileName,dir) > 0) {
            throw new ConflictException("目标目录已存在同名节点");
        }
    }

    private void requireNoSameNameExcluding(Long spaceId, Long parentId, String fileName, Integer dir, Long excludeId) {
        if(spaceFileMapper.countSameNameExcluding(spaceId,parentId,fileName,dir,excludeId) > 0) {
            throw new ConflictException("目标目录已存在同名节点");
        }
    }

    private void requireDepth(int depth) {
        if(depth > 100) {
            throw new ConflictException("目录层级不能超过 100 层");
        }
    }

    void requireNoDuplicateContent(Long spaceId, String contentHash) {
        if(contentHash == null || contentHash.isBlank()) {
            return;
        }
        SpaceFile duplicate = spaceFileMapper.findByContentHash(spaceId,contentHash);
        if(duplicate != null) {
            throw new ConflictException("相同文件已存在于 Space：" + duplicate.getPath());
        }
    }

    private void reserveContent(Long spaceId, String contentHash) {
        requireNoDuplicateContent(spaceId,contentHash);
        if(contentHash == null || contentHash.isBlank()) {
            return;
        }
        try {
            contentGuardMapper.reserve(spaceId,contentHash,LocalDateTime.now());
        } catch(DuplicateKeyException ex) {
            SpaceFile duplicate = spaceFileMapper.findByContentHash(spaceId,contentHash);
            String location = duplicate == null ? "另一个并发导入任务" : duplicate.getPath();
            throw new ConflictException("相同文件已存在于 Space：" + location);
        }
    }

    private void activateContentGuard(SpaceFile file) {
        if(file.getContentHash() != null && !file.getContentHash().isBlank()
                && contentGuardMapper.activate(file.getSpaceId(),file.getContentHash(),file.getId(),LocalDateTime.now()) != 1) {
            throw new BaseException("文件内容防重占位激活失败");
        }
    }

    private void releaseOrReassignContentGuard(SpaceFile removed) {
        if(removed.getContentHash() == null || removed.getContentHash().isBlank()) {
            return;
        }
        SpaceFile remaining = spaceFileMapper.findByContentHash(removed.getSpaceId(),removed.getContentHash());
        if(remaining == null) {
            contentGuardMapper.release(removed.getSpaceId(),removed.getContentHash(),removed.getId());
        } else {
            contentGuardMapper.reassign(removed.getSpaceId(),removed.getContentHash(),remaining.getId(),LocalDateTime.now());
        }
    }

    void replaceContentGuard(SpaceFile file, String newHash) {
        if(java.util.Objects.equals(file.getContentHash(),newHash)) return;
        reserveContent(file.getSpaceId(),newHash);
        String oldHash = file.getContentHash();
        if(spaceFileMapper.updateContentHash(file.getSpaceId(),file.getId(),newHash,LocalDateTime.now()) != 1) {
            throw new BaseException("文件内容防重信息更新失败");
        }
        file.setContentHash(newHash);
        activateContentGuard(file);
        if(oldHash != null && !oldHash.isBlank()) {
            SpaceFile remaining = spaceFileMapper.findByContentHash(file.getSpaceId(),oldHash);
            if(remaining == null) contentGuardMapper.release(file.getSpaceId(),oldHash,file.getId());
            else contentGuardMapper.reassign(file.getSpaceId(),oldHash,remaining.getId(),LocalDateTime.now());
        }
    }

    /**
     * 构建 buildChildren 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param parentId 父级 ID
     * @return 列表结果
     */
    private List<SpaceFileVO> buildChildren(Long spaceId, Long parentId, Long userId) {
        List<SpaceFileVO> children = new ArrayList<>();
        for(SpaceFile child : spaceFileMapper.listByParentId(spaceId,parentId)) {
            SpaceFileVO vo = toVO(child,userId);
            if(child.getDir() == 1) {
                vo.setChildren(buildChildren(spaceId,child.getId(),userId));
            }
            children.add(vo);
        }
        return children;
    }

    /**
     * 转换 toVOList 相关逻辑。
     *
     * @param files 方法入参
     * @return 列表结果
     */
    private List<SpaceFileVO> toVOList(List<SpaceFile> files, Long userId) {
        List<SpaceFileVO> result = new ArrayList<>();
        files.forEach(file -> result.add(toVO(file,userId)));
        return result;
    }

    /**
     * 转换 toVO 相关逻辑。
     *
     * @param spaceFile 空间文件对象
     * @return 处理结果
     */
    private SpaceFileVO toVO(SpaceFile spaceFile, Long userId) {
        SpaceFileVO vo = new SpaceFileVO();
        vo.setId(spaceFile.getId());
        vo.setSpaceId(spaceFile.getSpaceId());
        vo.setFileUuid(spaceFile.getFileUuid());
        vo.setName(spaceFile.getFileName());
        vo.setDir(spaceFile.getDir() == 1);
        vo.setParentId(spaceFile.getParentId());
        vo.setPath(spaceFile.getPath());
        vo.setNodeVersion(spaceFile.getNodeVersion());
        vo.setDepth(spaceFile.getDepth());
        vo.setLifecycleState(spaceFile.getLifecycleState());
        vo.setCreatedBy(spaceFile.getCreatedBy());
        vo.setVersionEnabled(spaceFile.getVersionEnabled());
        vo.setEffectiveVersionEnabled(resolveEffectiveVersionEnabled(spaceFile));
        vo.setKnowledgeState(spaceFile.getKnowledgeState());
        vo.setKnowledgeVersion(spaceFile.getKnowledgeVersion());
        vo.setSearchable(Integer.valueOf(1).equals(spaceFile.getSearchable()));
        vo.setLastKnowledgeError(spaceFile.getLastKnowledgeError());
        vo.setRemovedAt(spaceFile.getRemovedAt());
        vo.setCreatetime(spaceFile.getCreatetime());
        vo.setUpdatetime(spaceFile.getUpdatetime());
        if(spaceFile.getDir() == 0 && spaceFile.getFileUuid() != null) {
            File file = fileInfoMapper.getFileByFileUuid(spaceFile.getFileUuid(),spaceFile.getCreatedBy());
            if(file != null) {
                vo.setType(file.getType());
                vo.setSize(file.getSize());
            }
        } else {
            vo.setType("dir");
        }
        vo.setCapability(spaceFileAccessService.capabilities(spaceFile,userId));
        return vo;
    }

    /**
     * 更新 updateVersionEnabled 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param fileId 文件 ID
     * @param versionEnabled 方法入参
     * @param userId 用户 ID
     * @return 处理结果
     */
    @Transactional
    public SpaceFileVO updateVersionEnabled(Long spaceId, Long fileId, Integer versionEnabled, Long userId) {
        spaceFileAccessService.requireNodeAction(spaceId,fileId,userId,SpaceFileAction.VERSION_MANAGE);
        if(versionEnabled != null && !StatusConstant.ENABLE.equals(versionEnabled) && !StatusConstant.DISABLE.equals(versionEnabled)) {
            throw new BaseException("文件历史版本开关只能为 1、0 或 null");
        }
        SpaceFile file = spaceFileMapper.getById(spaceId,fileId);
        if(file == null || file.getDir() == 1) {
            throw new NotFoundException("空间文件不存在或不是普通文件");
        }
        int rows = spaceFileMapper.updateVersionEnabled(spaceId,fileId,versionEnabled,LocalDateTime.now());
        if(rows == 0) {
            throw new BaseException("文件历史版本设置更新失败");
        }
        SpaceFile updated = spaceFileMapper.getById(spaceId,fileId);
        ensureInitialVersionIfEnabled(updated,userId);
        return toVO(updated,userId);
    }

    /**
     * 查询 listVersionEnabledFiles 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param userId 用户 ID
     * @return 列表结果
     */
    public List<SpaceFileVO> listVersionEnabledFiles(Long spaceId, Long userId) {
        spaceFileAccessService.requireRead(spaceId,userId);
        List<SpaceFileVO> result = new ArrayList<>();
        for(SpaceFile file : spaceFileMapper.listAll(spaceId)) {
            if(file.getDir() == 0 && resolveEffectiveVersionEnabled(file)) {
                result.add(toVO(file,userId));
            }
        }
        return result;
    }

    /**
     * 解析 resolveEffectiveVersionEnabled 相关逻辑。
     *
     * @param spaceFile 空间文件对象
     * @return 处理结果
     */
    public Boolean resolveEffectiveVersionEnabled(SpaceFile spaceFile) {
        if(spaceFile.getVersionEnabled() != null) {
            return StatusConstant.ENABLE.equals(spaceFile.getVersionEnabled());
        }
        Space space = spaceService.requireSpace(spaceFile.getSpaceId());
        return StatusConstant.ENABLE.equals(space.getVersionEnabled());
    }

    private void ensureInitialVersionIfEnabled(SpaceFile spaceFile, Long userId) {
        if(spaceFile != null && spaceFile.getDir() == 0 && resolveEffectiveVersionEnabled(spaceFile)) {
            initialFileVersionService.ensureInitialVersion(spaceFile.getFileUuid(),spaceFile.getFileName(),userId);
        }
    }

    private StoredPhysicalFile storeMultipartFile(MultipartFile uploadFile,
                                                  String fileName,
                                                  FileFingerprint fingerprint,
                                                  String reservedFileUuid) {
        File existingFile = fileInfoMapper.getFileByHash(fingerprint.hash());
        if(existingFile != null) {
            if(fileInfoMapper.updateFileCount(existingFile.getFileUuid(),1) == 0) {
                throw new BaseException("文件引用计数更新失败");
            }
            return new StoredPhysicalFile(existingFile.getFileUuid());
        }

        String fileUuid = reservedFileUuid == null ? UuidUtil.randomUuid() : reservedFileUuid;
        LocalDateTime now = LocalDateTime.now();
        File file = File.builder()
                .fileUuid(fileUuid)
                .dir(false)
                .name(fileName)
                .type(getFileType(fileName))
                .size(uploadFile.getSize())
                .hash(fingerprint.hash())
                .md5(fingerprint.md5())
                .sha1(fingerprint.sha1())
                .status(StatusConstant.ENABLE)
                .count(1)
                .createTime(now)
                .updateTime(now)
                .build();
        try {
            crossStoreFileWriteService.putNewObject(uploadFile,fileUuid);
        } catch (Exception e) {
            throw new RuntimeException("空间文件上传失败",e);
        }
        if(fileInfoMapper.insertFileInfo(file) == 0) {
            throw new BaseException("文件元数据保存失败");
        }
        return new StoredPhysicalFile(fileUuid);
    }

    private StoredPhysicalFile storeGeneratedFile(String fileName, byte[] content, String contentType, String reservedFileUuid) {
        if(content == null || content.length == 0) {
            throw new BaseException("文件内容不能为空");
        }
        if(siteSettingService.exceedsUploadLimit(content.length,maxFileSize)) {
            throw new BaseException("文件大小超过限制");
        }

        String md5;
        String sha1;
        String hash;
        try {
            md5 = Md5Util.md5(new ByteArrayInputStream(content));
            sha1 = HashUtil.sha1(new ByteArrayInputStream(content));
            hash = HashUtil.sha256(new ByteArrayInputStream(content));
        } catch (IOException e) {
            throw new RuntimeException("文件哈希计算失败",e);
        }

        File existingFile = fileInfoMapper.getFileByHash(hash);
        if(existingFile != null) {
            if(fileInfoMapper.updateFileCount(existingFile.getFileUuid(),1) == 0) {
                throw new BaseException("文件引用计数更新失败");
            }
            return new StoredPhysicalFile(existingFile.getFileUuid());
        }

        String fileUuid = reservedFileUuid == null ? UuidUtil.randomUuid() : reservedFileUuid;
        LocalDateTime now = LocalDateTime.now();
        File file = File.builder()
                .fileUuid(fileUuid)
                .dir(false)
                .name(fileName)
                .type(getFileType(fileName))
                .size((long) content.length)
                .hash(hash)
                .md5(md5)
                .sha1(sha1)
                .status(StatusConstant.ENABLE)
                .count(1)
                .createTime(now)
                .updateTime(now)
                .build();
        try {
            crossStoreFileWriteService.putNewObject(new ByteArrayInputStream(content),content.length,contentType,fileUuid);
        } catch (Exception e) {
            throw new RuntimeException("空间文件上传失败",e);
        }
        if(fileInfoMapper.insertFileInfo(file) == 0) {
            throw new BaseException("文件元数据保存失败");
        }
        return new StoredPhysicalFile(fileUuid);
    }

    private SpaceFile createSpaceFile(Long spaceId, SpaceFile parent, String fileUuid, String fileName, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        SpaceFile spaceFile = new SpaceFile();
        spaceFile.setSpaceId(spaceId);
        spaceFile.setFileUuid(fileUuid);
        spaceFile.setFileName(fileName);
        spaceFile.setDir(0);
        spaceFile.setParentId(parent.getId());
        spaceFile.setPath(buildPath(parent,fileName,false));
        requireDepth(parent.getDepth() + 1);
        spaceFile.setDepth(parent.getDepth() + 1);
        spaceFile.setNodeVersion(1L);
        spaceFile.setLifecycleState("ACTIVE");
        File physical = fileInfoMapper.getFileByFileUuid(fileUuid,userId);
        if(physical != null) {
            spaceFile.setContentHash(physical.getHash());
            reserveContent(spaceId,physical.getHash());
        }
        spaceFile.setStatus(StatusConstant.ENABLE);
        spaceFile.setCreatedBy(userId);
        spaceFile.setCreatetime(now);
        spaceFile.setUpdatetime(now);
        if(spaceFileMapper.insert(spaceFile) == 0) {
            throw new BaseException("空间文件保存失败");
        }
        activateContentGuard(spaceFile);
        if(quotaService != null) quotaService.recordTeamFile(spaceFile.getId(),spaceId,fileUuid);
        return spaceFile;
    }

    private String quotaKey(File file) {
        return file.getHash() == null || file.getHash().isBlank() ? "uuid:" + file.getFileUuid() : file.getHash();
    }

    private void validateUploadFile(MultipartFile uploadFile) {
        if(uploadFile == null || uploadFile.isEmpty()) {
            throw new BaseException("上传文件不能为空");
        }
        if(siteSettingService.exceedsUploadLimit(uploadFile.getSize(),maxFileSize)) {
            throw new BaseException("文件大小超过限制");
        }
        requireSafeFileName(uploadFile.getOriginalFilename());
    }

    private URI requireHttpUri(String url) {
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            if(scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                throw new BaseException("只支持 http 或 https 链接");
            }
            if(uri.getHost() == null || uri.getHost().isBlank()) {
                throw new BaseException("网页链接缺少主机名");
            }
            requirePublicHost(uri.getHost());
            return uri;
        } catch (IllegalArgumentException e) {
            throw new BaseException("网页链接格式不正确");
        }
    }

    private void requirePublicHost(String host) {
        try {
            for(InetAddress address : InetAddress.getAllByName(host)) {
                if(address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
                    throw new BaseException("不允许导入内网或本机地址");
                }
            }
        } catch (BaseException e) {
            throw e;
        } catch (Exception e) {
            throw new BaseException("网页链接主机名解析失败");
        }
    }

    private WebPageSnapshot fetchWebPage(URI uri) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(8))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(java.time.Duration.ofSeconds(15))
                    .header("User-Agent","YLCloud-RAG-Link-Importer/1.0")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if(status < 200 || status >= 300) {
                throw new BaseException("网页请求失败，状态码 " + status);
            }
            String contentType = response.headers().firstValue("content-type").orElse("text/plain");
            if(!isTextualContentType(contentType)) {
                throw new BaseException("网页链接内容不是可索引文本");
            }
            byte[] body = readLimited(response.body(),maxWebLinkSize);
            String raw = new String(body,resolveCharset(contentType));
            String title = extractTitle(raw,uri);
            String text = contentType.toLowerCase().contains("html") ? htmlToText(raw) : raw;
            return new WebPageSnapshot(title,contentType,text);
        } catch (BaseException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("网页链接导入失败",e);
        }
    }

    private boolean isTextualContentType(String contentType) {
        String lower = contentType == null ? "" : contentType.toLowerCase();
        return lower.startsWith("text/")
                || lower.contains("application/json")
                || lower.contains("application/xml")
                || lower.contains("application/xhtml+xml")
                || lower.contains("application/javascript")
                || lower.contains("application/x-ndjson");
    }

    private byte[] readLimited(InputStream inputStream, long maxBytes) throws IOException {
        try(inputStream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while((read = inputStream.read(buffer)) != -1) {
                total += read;
                if(total > maxBytes) {
                    throw new BaseException("网页内容超过导入大小限制");
                }
                output.write(buffer,0,read);
            }
            return output.toByteArray();
        }
    }

    private java.nio.charset.Charset resolveCharset(String contentType) {
        if(contentType != null) {
            String lower = contentType.toLowerCase();
            int index = lower.indexOf("charset=");
            if(index >= 0) {
                String charset = contentType.substring(index + 8).trim().replace("\"","");
                try {
                    return java.nio.charset.Charset.forName(charset);
                } catch (Exception ignored) {
                    return StandardCharsets.UTF_8;
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private String extractTitle(String raw, URI uri) {
        Matcher matcher = TITLE_PATTERN.matcher(raw == null ? "" : raw);
        if(matcher.find()) {
            String title = htmlToText(matcher.group(1)).trim();
            if(!title.isBlank()) {
                return title;
            }
        }
        return uri.getHost();
    }

    private String htmlToText(String html) {
        if(html == null) {
            return "";
        }
        return html
                .replaceAll("(?is)<script[^>]*>.*?</script>"," ")
                .replaceAll("(?is)<style[^>]*>.*?</style>"," ")
                .replaceAll("(?i)<br\\s*/?>","\n")
                .replaceAll("(?i)</p>","\n")
                .replaceAll("(?i)</h[1-6]>","\n")
                .replaceAll("(?is)<[^>]+>"," ")
                .replace("&nbsp;"," ")
                .replace("&amp;","&")
                .replace("&lt;","<")
                .replace("&gt;",">")
                .replace("&quot;","\"")
                .replace("&#39;","'")
                .replaceAll("[ \\t\\x0B\\f\\r]+"," ")
                .replaceAll("\\n\\s+","\n")
                .replaceAll("\\n{3,}","\n\n")
                .trim();
    }

    private String defaultLinkFileName(String title, URI uri) {
        String base = title == null || title.isBlank() ? uri.getHost() : title;
        return sanitizeFileName(base) + ".md";
    }

    private String sanitizeFileName(String value) {
        String sanitized = value == null ? "web-link" : value.replaceAll("[\\\\/:*?\"<>|]"," ").replaceAll("\\s+"," ").trim();
        if(sanitized.isBlank()) {
            return "web-link";
        }
        return sanitized.length() > 120 ? sanitized.substring(0,120).trim() : sanitized;
    }

    private String ensureMarkdownExtension(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".md") || lower.endsWith(".txt") ? fileName : fileName + ".md";
    }

    private String buildWebLinkMarkdown(URI uri, WebPageSnapshot snapshot) {
        return "# " + snapshot.title() + "\n\n"
                + "- Source: " + uri + "\n"
                + "- Content-Type: " + snapshot.contentType() + "\n"
                + "- Imported-At: " + LocalDateTime.now() + "\n\n"
                + snapshot.text() + "\n";
    }

    private String getFileType(String fileName) {
        if(fileName == null) {
            return ".txt";
        }
        int dotIndex = fileName.lastIndexOf(".");
        if(dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return ".txt";
        }
        return fileName.substring(dotIndex);
    }

    private String requireSafeFileName(String fileName) {
        if(fileName == null) {
            throw new BaseException("文件名不能为空");
        }
        String normalized = fileName.trim();
        if(normalized.isEmpty() || normalized.length() > 255) {
            throw new BaseException("文件名不能为空且不能超过 255 个字符");
        }
        if(normalized.contains("/") || normalized.contains("\\") || normalized.contains("..")) {
            throw new BaseException("文件名包含非法路径字符");
        }
        return normalized;
    }

    private FileFingerprint calculateFingerprint(MultipartFile uploadFile) {
        try {
            return new FileFingerprint(
                    Md5Util.md5(uploadFile.getInputStream()),
                    HashUtil.sha1(uploadFile.getInputStream()),
                    HashUtil.sha256(uploadFile.getInputStream()));
        } catch (IOException e) {
            throw new RuntimeException("文件哈希计算失败",e);
        }
    }

    private SpaceFileVO replaySpaceUpload(CrossStoreOperation operation, Long spaceId, Long parentId) {
        if(operation.getResultRef() == null || !operation.getResultRef().matches("\\d+")) {
            throw new BaseException("上传已完成，但结果引用不可用");
        }
        SpaceFile file = spaceFileMapper.getById(spaceId,Long.valueOf(operation.getResultRef()));
        if(file == null || !parentId.equals(file.getParentId())) {
            throw new BaseException("幂等上传结果已不在原目录，请使用新的 Idempotency-Key");
        }
        return toVO(file,file.getCreatedBy());
    }

    private void requireValidIdempotencyKey(String value) {
        if(value.length() > 128 || !value.matches("^[A-Za-z0-9._:-]{8,128}$")) {
            throw new BaseException("Idempotency-Key 格式不正确");
        }
    }

    private record StoredPhysicalFile(String fileUuid) {}

    private record FileFingerprint(String md5, String sha1, String hash) {}

    private record WebPageSnapshot(String title, String contentType, String text) {}

    /**
     * 构建 buildPath 相关逻辑。
     *
     * @param parent 方法入参
     * @param name 名称
     * @param dir 方法入参
     * @return 处理结果
     */
    private String buildPath(SpaceFile parent, String name, boolean dir) {
        String parentPath = parent.getPath() == null ? "/" : parent.getPath();
        if(!parentPath.endsWith("/")) {
            parentPath = parentPath + "/";
        }
        String path = parentPath + name;
        return dir ? path + "/" : path;
    }
}
