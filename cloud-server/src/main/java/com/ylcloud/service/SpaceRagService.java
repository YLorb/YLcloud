package com.ylcloud.service;

import com.ylcloud.DTO.SpaceDocumentSearchDTO;
import com.ylcloud.DTO.SpaceRagConfigUpdateDTO;
import com.ylcloud.DTO.SpaceRagQueryDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.VO.SpaceDocumentChunkHitVO;
import com.ylcloud.VO.SpaceDocumentSearchVO;
import com.ylcloud.VO.SpaceRagConfigVO;
import com.ylcloud.VO.SpaceRagCitationVO;
import com.ylcloud.VO.SpaceRagDocumentVO;
import com.ylcloud.VO.SpaceRagQueryVO;
import com.ylcloud.VO.SpaceRagTaskVO;
import com.ylcloud.config.RagProperties;
import com.ylcloud.constant.SpaceConstant;
import com.ylcloud.constant.StatusConstant;
import com.ylcloud.entity.File;
import com.ylcloud.entity.FileRagChunk;
import com.ylcloud.entity.SpaceFile;
import com.ylcloud.entity.SpaceRagChunkRef;
import com.ylcloud.entity.SpaceRagConfig;
import com.ylcloud.entity.SpaceRagDocument;
import com.ylcloud.entity.SpaceRagQueryLog;
import com.ylcloud.entity.SpaceRagTask;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.FileRagChunkMapper;
import com.ylcloud.mapper.SpaceFileMapper;
import com.ylcloud.mapper.SpaceRagChunkRefMapper;
import com.ylcloud.mapper.SpaceRagDocumentMapper;
import com.ylcloud.mapper.SpaceRagMapper;
import com.ylcloud.mapper.SpaceRagQueryLogMapper;
import com.ylcloud.mapper.SpaceRagTaskMapper;
import com.ylcloud.service.rag.DocumentTextExtractor;
import com.ylcloud.service.rag.ExtractedDocumentText;
import com.ylcloud.service.rag.QdrantVectorStoreService;
import com.ylcloud.service.rag.RagChatService;
import com.ylcloud.service.rag.RagRerankService;
import com.ylcloud.service.rag.RagTaskExecutorService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 空间 RAG 业务服务。
 */
@Service
public class SpaceRagService {
    private static final int DEFAULT_CHUNK_SIZE = 1000;
    private static final int DEFAULT_CHUNK_OVERLAP = 100;
    private static final int DEFAULT_TOP_K = 5;

    private final SpaceRagMapper spaceRagMapper;
    private final SpaceRagDocumentMapper spaceRagDocumentMapper;
    private final FileRagChunkMapper fileRagChunkMapper;
    private final SpaceRagChunkRefMapper spaceRagChunkRefMapper;
    private final SpaceRagTaskMapper spaceRagTaskMapper;
    private final SpaceRagQueryLogMapper spaceRagQueryLogMapper;
    private final SpaceFileMapper spaceFileMapper;
    private final FileInfoMapper fileInfoMapper;
    private final SpacePermissionService spacePermissionService;
    private final QdrantVectorStoreService qdrantVectorStoreService;
    private final RagChatService ragChatService;
    private final RagRerankService ragRerankService;
    private final RagProperties ragProperties;
    private final DocumentTextExtractor documentTextExtractor;
    private final RagTaskExecutorService ragTaskExecutorService;

    public SpaceRagService(SpaceRagMapper spaceRagMapper,
                           SpaceRagDocumentMapper spaceRagDocumentMapper,
                           FileRagChunkMapper fileRagChunkMapper,
                           SpaceRagChunkRefMapper spaceRagChunkRefMapper,
                           SpaceRagTaskMapper spaceRagTaskMapper,
                           SpaceRagQueryLogMapper spaceRagQueryLogMapper,
                           SpaceFileMapper spaceFileMapper,
                           FileInfoMapper fileInfoMapper,
                           SpacePermissionService spacePermissionService,
                           QdrantVectorStoreService qdrantVectorStoreService,
                           RagChatService ragChatService,
                           RagRerankService ragRerankService,
                           RagProperties ragProperties,
                           DocumentTextExtractor documentTextExtractor,
                           RagTaskExecutorService ragTaskExecutorService) {
        this.spaceRagMapper = spaceRagMapper;
        this.spaceRagDocumentMapper = spaceRagDocumentMapper;
        this.fileRagChunkMapper = fileRagChunkMapper;
        this.spaceRagChunkRefMapper = spaceRagChunkRefMapper;
        this.spaceRagTaskMapper = spaceRagTaskMapper;
        this.spaceRagQueryLogMapper = spaceRagQueryLogMapper;
        this.spaceFileMapper = spaceFileMapper;
        this.fileInfoMapper = fileInfoMapper;
        this.spacePermissionService = spacePermissionService;
        this.qdrantVectorStoreService = qdrantVectorStoreService;
        this.ragChatService = ragChatService;
        this.ragRerankService = ragRerankService;
        this.ragProperties = ragProperties;
        this.documentTextExtractor = documentTextExtractor;
        this.ragTaskExecutorService = ragTaskExecutorService;
    }

    /**
     * 查询空间 RAG 配置。
     *
     * @param spaceId 空间 ID
     * @param userId 当前用户 ID
     * @return 空间 RAG 配置
     */
    public SpaceRagConfigVO getConfig(Long spaceId, Long userId) {
        spacePermissionService.requireMember(spaceId,userId);
        return toConfigVO(requireConfig(spaceId));
    }

    /**
     * 更新空间 RAG 配置。
     *
     * @param spaceId 空间 ID
     * @param dto 更新参数
     * @param userId 当前用户 ID
     * @return 更新后的空间 RAG 配置
     */
    @Transactional
    public SpaceRagConfigVO updateConfig(Long spaceId, SpaceRagConfigUpdateDTO dto, Long userId) {
        spacePermissionService.requireAdmin(spaceId,userId);
        SpaceRagConfig current = requireConfig(spaceId);
        Integer chunkSize = dto.getChunkSize() == null ? current.getChunkSize() : dto.getChunkSize();
        Integer chunkOverlap = dto.getChunkOverlap() == null ? current.getChunkOverlap() : dto.getChunkOverlap();
        if(chunkOverlap >= chunkSize) {
            throw new BaseException("文本分块重叠长度必须小于文本分块大小");
        }
        int rows = spaceRagMapper.updateConfig(
                spaceId,
                blankToCurrent(dto.getEmbeddingModel(),current.getEmbeddingModel()),
                blankToCurrent(dto.getChatModel(),current.getChatModel()),
                chunkSize,
                chunkOverlap,
                dto.getTopK() == null ? current.getTopK() : dto.getTopK(),
                dto.getScoreThreshold() == null ? current.getScoreThreshold() : dto.getScoreThreshold(),
                dto.getEnabled() == null ? current.getEnabled() : dto.getEnabled(),
                LocalDateTime.now()
        );
        if(rows == 0) {
            throw new BaseException("空间 RAG 配置更新失败");
        }
        return toConfigVO(requireConfig(spaceId));
    }

    /**
     * 查询空间 RAG。
     *
     * @param spaceId 空间 ID
     * @param dto 查询参数
     * @param userId 当前用户 ID
     * @return 查询结果
     */
    @Transactional
    public SpaceRagQueryVO query(Long spaceId, SpaceRagQueryDTO dto, Long userId) {
        spacePermissionService.requireMember(spaceId,userId);
        SpaceRagConfig config = requireConfig(spaceId);
        if(!StatusConstant.ENABLE.equals(config.getEnabled())) {
            throw new BaseException("当前空间未启用 RAG");
        }
        int limit = dto.getTopK() == null ? safeTopK(config.getTopK()) : dto.getTopK();
        List<FileRagChunk> chunks = searchChunks(spaceId,dto.getQuestion(),limit,config);
        String answer = ragChatService.answer(dto.getQuestion(),chunks,config);
        List<Long> hitChunkIds = new ArrayList<>();
        List<String> contexts = new ArrayList<>();
        StringJoiner idJoiner = new StringJoiner(",");
        for(FileRagChunk chunk : chunks) {
            hitChunkIds.add(chunk.getId());
            contexts.add(chunk.getContent());
            idJoiner.add(String.valueOf(chunk.getId()));
        }
        saveQueryLog(spaceId,userId,dto.getQuestion(),answer,idJoiner.toString(),config.getChatModel(),true,null);

        SpaceRagQueryVO vo = new SpaceRagQueryVO();
        vo.setQuestion(dto.getQuestion());
        vo.setAnswer(answer);
        vo.setHitChunkIds(hitChunkIds);
        vo.setContexts(contexts);
        vo.setCitations(buildCitations(spaceId,chunks));
        return vo;
    }

    /**
     * 查询空间内已进入 RAG 的文档。
     *
     * @param spaceId 空间 ID
     * @param userId 当前用户 ID
     * @return RAG 文档列表
     */
    public List<SpaceRagDocumentVO> listDocuments(Long spaceId, Long userId) {
        spacePermissionService.requireAdmin(spaceId,userId);
        List<SpaceRagDocumentVO> result = new ArrayList<>();
        for(SpaceRagDocument document : spaceRagDocumentMapper.listBySpaceId(spaceId)) {
            result.add(toDocumentVO(document));
        }
        return result;
    }

    /**
     * 查询空间 RAG 任务。
     *
     * @param spaceId 空间 ID
     * @param userId 当前用户 ID
     * @return RAG 任务列表
     */
    public List<SpaceRagTaskVO> listTasks(Long spaceId, Long userId) {
        spacePermissionService.requireAdmin(spaceId,userId);
        List<SpaceRagTaskVO> result = new ArrayList<>();
        for(SpaceRagTask task : spaceRagTaskMapper.listBySpaceId(spaceId)) {
            result.add(toTaskVO(task));
        }
        return result;
    }

    /**
     * 搜索空间中的 RAG 文档。
     *
     * @param spaceId 空间 ID
     * @param dto 搜索参数
     * @param userId 当前用户 ID
     * @return 文档搜索结果
     */
    public List<SpaceDocumentSearchVO> searchDocuments(Long spaceId, SpaceDocumentSearchDTO dto, Long userId) {
        spacePermissionService.requireMember(spaceId,userId);
        String keyword = dto.getKeyword() == null ? "" : dto.getKeyword().trim();
        int page = dto.getPage() == null || dto.getPage() < 1 ? 1 : dto.getPage();
        int pageSize = dto.getPageSize() == null || dto.getPageSize() < 1 ? 20 : Math.min(dto.getPageSize(),100);
        int offset = (page - 1) * pageSize;

        List<SpaceDocumentSearchVO> metadataHits = spaceRagDocumentMapper.searchDocuments(
                spaceId,
                keyword,
                dto.getFileType(),
                dto.getIndexStatus(),
                pageSize,
                offset
        );
        Map<Long, SpaceDocumentSearchVO> resultMap = new LinkedHashMap<>();
        for(SpaceDocumentSearchVO vo : metadataHits) {
            fillDocumentLinks(spaceId,vo);
            vo.setHitContents(new ArrayList<>());
            vo.setHitChunkIds(new ArrayList<>());
            resultMap.put(vo.getDocumentId(),vo);
        }

        boolean searchContent = (dto.getSearchContent() == null || dto.getSearchContent() == 1) && !keyword.isBlank();
        if(searchContent) {
            List<SpaceDocumentChunkHitVO> chunkHits = fileRagChunkMapper.searchDocumentChunkHits(spaceId,keyword,pageSize * 5);
            List<Long> missingDocumentIds = new ArrayList<>();
            for(SpaceDocumentChunkHitVO hit : chunkHits) {
                if(!resultMap.containsKey(hit.getDocumentId()) && !missingDocumentIds.contains(hit.getDocumentId())) {
                    missingDocumentIds.add(hit.getDocumentId());
                }
            }
            if(!missingDocumentIds.isEmpty()) {
                for(SpaceDocumentSearchVO vo : spaceRagDocumentMapper.listSearchDocumentsByIds(spaceId,missingDocumentIds)) {
                    fillDocumentLinks(spaceId,vo);
                    vo.setHitContents(new ArrayList<>());
                    vo.setHitChunkIds(new ArrayList<>());
                    resultMap.put(vo.getDocumentId(),vo);
                }
            }
            for(SpaceDocumentChunkHitVO hit : chunkHits) {
                SpaceDocumentSearchVO vo = resultMap.get(hit.getDocumentId());
                if(vo == null) continue;
                vo.getHitChunkIds().add(hit.getChunkId());
                vo.getHitContents().add(truncate(hit.getContent(),200));
            }
        }
        return new ArrayList<>(resultMap.values());
    }

    /**
     * 重建整个空间的 RAG 引用索引。
     *
     * @param spaceId 空间 ID
     * @param userId 当前用户 ID
     * @return 是否成功
     */
    @Transactional
    public Boolean rebuildSpace(Long spaceId, Long userId) {
        spacePermissionService.requireAdmin(spaceId,userId);
        SpaceRagTask runningTask = spaceRagTaskMapper.findRunningIndexTaskBySpace(spaceId);
        if(runningTask != null) {
            return true;
        }
        SpaceRagTask task = createTask(spaceId,null,null,SpaceConstant.RAG_TASK_REBUILD_SPACE,userId);
        dispatchAfterCommit(() -> ragTaskExecutorService.runSpaceTask(task.getId(),spaceId,userId));
        return true;
    }

    /**
     * 重建单个空间文件的 RAG 引用索引。
     *
     * @param spaceId 空间 ID
     * @param spaceFileId 空间文件节点 ID
     * @param userId 当前用户 ID
     * @return 是否成功
     */
    @Transactional
    public Boolean rebuildFile(Long spaceId, Long spaceFileId, Long userId) {
        spacePermissionService.requireAdmin(spaceId,userId);
        SpaceRagTask runningSpaceTask = spaceRagTaskMapper.findRunningSpaceTask(spaceId,SpaceConstant.RAG_TASK_REBUILD_SPACE);
        if(runningSpaceTask != null) {
            return true;
        }
        SpaceRagTask runningTask = spaceRagTaskMapper.findRunningFileTask(spaceId,spaceFileId);
        if(runningTask != null) {
            return true;
        }
        SpaceRagDocument document = spaceRagDocumentMapper.getBySpaceFileId(spaceId,spaceFileId);
        if(document == null) {
            document = createDocument(requireFile(spaceId,spaceFileId),userId);
        }
        SpaceRagTask task = createTask(spaceId,spaceFileId,document.getId(),SpaceConstant.RAG_TASK_REBUILD_FILE,userId);
        Long documentId = document.getId();
        dispatchAfterCommit(() -> ragTaskExecutorService.runFileTask(task.getId(),documentId,userId));
        return true;
    }

    /**
     * 空间导入文件后，建立空间到全局文件 chunk 的引用。
     *
     * @param spaceFile 空间文件节点
     * @param userId 当前用户 ID
     */
    @Transactional
    public void handleFileImported(SpaceFile spaceFile, Long userId) {
        if(spaceFile == null || spaceFile.getDir() == 1) {
            return;
        }
        SpaceRagDocument document = createDocument(spaceFile,userId);
        SpaceRagTask runningSpaceTask = spaceRagTaskMapper.findRunningSpaceTask(spaceFile.getSpaceId(),SpaceConstant.RAG_TASK_REBUILD_SPACE);
        if(runningSpaceTask != null) {
            return;
        }
        SpaceRagTask runningTask = spaceRagTaskMapper.findRunningFileTask(spaceFile.getSpaceId(),spaceFile.getId());
        if(runningTask != null) {
            return;
        }
        SpaceRagTask task = createTask(spaceFile.getSpaceId(),spaceFile.getId(),document.getId(),SpaceConstant.RAG_TASK_INDEX_FILE,userId);
        dispatchAfterCommit(() -> ragTaskExecutorService.runFileTask(task.getId(),document.getId(),userId));
    }

    public void executeFileRagTask(Long taskId, Long documentId, Long userId) {
        LocalDateTime started = LocalDateTime.now();
        try {
            markTaskRunning(taskId,started);
            SpaceRagDocument document = spaceRagDocumentMapper.getById(documentId);
            if(document == null) {
                throw new BaseException("RAG document not found");
            }
            rebuildDocument(document,userId);
            finishTask(taskId,SpaceConstant.RAG_TASK_SUCCESS,null,started);
        } catch (Exception ex) {
            String errorMessage = truncate(ex.getMessage(),1000);
            finishTask(taskId,SpaceConstant.RAG_TASK_FAILED,errorMessage,started);
            if(documentId != null) {
                spaceRagDocumentMapper.updateIndexResult(documentId,SpaceConstant.RAG_INDEX_FAILED,0,errorMessage,LocalDateTime.now());
            }
        }
    }

    public void executeSpaceRagTask(Long taskId, Long spaceId, Long userId) {
        LocalDateTime started = LocalDateTime.now();
        try {
            markTaskRunning(taskId,started);
            List<SpaceRagDocument> documents = spaceRagDocumentMapper.listBySpaceId(spaceId);
            int failed = 0;
            for(SpaceRagDocument document : documents) {
                try {
                    rebuildDocument(document,userId);
                } catch (Exception ex) {
                    failed++;
                    spaceRagDocumentMapper.updateIndexResult(
                            document.getId(),
                            SpaceConstant.RAG_INDEX_FAILED,
                            0,
                            truncate(ex.getMessage(),1000),
                            LocalDateTime.now()
                    );
                }
            }
            if(failed > 0) {
                finishTask(taskId,SpaceConstant.RAG_TASK_FAILED,"Partial document indexing failed: " + failed + "/" + documents.size(),started);
                return;
            }
            finishTask(taskId,SpaceConstant.RAG_TASK_SUCCESS,null,started);
        } catch (Exception ex) {
            finishTask(taskId,SpaceConstant.RAG_TASK_FAILED,truncate(ex.getMessage(),1000),started);
        }
    }

    /**
     * 空间删除文件后，仅禁用该空间对全局文件 chunk 的引用。
     *
     * @param spaceId 空间 ID
     * @param spaceFileId 空间文件节点 ID
     * @param userId 当前用户 ID
     */
    @Transactional
    public void handleFileRemoved(Long spaceId, Long spaceFileId, Long userId) {
        SpaceRagDocument document = spaceRagDocumentMapper.getBySpaceFileId(spaceId,spaceFileId);
        SpaceRagTask task = createTask(spaceId,spaceFileId,document == null ? null : document.getId(),SpaceConstant.RAG_TASK_DELETE_FILE,userId);
        LocalDateTime started = LocalDateTime.now();
        spaceRagChunkRefMapper.disableBySpaceFileId(spaceId,spaceFileId,LocalDateTime.now());
        qdrantVectorStoreService.deleteBySpaceFile(spaceId,spaceFileId);
        spaceRagDocumentMapper.disableBySpaceFileId(spaceId,spaceFileId,SpaceConstant.RAG_INDEX_FAILED,"空间文件已删除",LocalDateTime.now());
        finishTask(task,SpaceConstant.RAG_TASK_SUCCESS,null,started);
    }

    private void rebuildDocument(SpaceRagDocument document, Long userId) {
        SpaceFile spaceFile = requireFile(document.getSpaceId(),document.getSpaceFileId());
        SpaceRagConfig config = requireConfig(document.getSpaceId());
        LocalDateTime now = LocalDateTime.now();
        File currentFile = fileInfoMapper.getFileByFileUuid(spaceFile.getFileUuid(),spaceFile.getCreatedBy());
        if(currentFile != null) {
            spaceRagDocumentMapper.updateFileMeta(document.getId(),spaceFile.getFileName(),currentFile.getHash(),currentFile.getType(),now);
        }
        spaceRagDocumentMapper.updateIndexResult(document.getId(),SpaceConstant.RAG_INDEX_INDEXING,0,null,now);
        spaceRagChunkRefMapper.disableByDocumentId(document.getId(),now);

        List<FileRagChunk> fileChunks = ensureFileChunks(spaceFile,config);
        int refCount = 0;
        for(FileRagChunk fileChunk : fileChunks) {
            if(spaceRagChunkRefMapper.countActiveRef(document.getSpaceId(),document.getId(),fileChunk.getId()) > 0) {
                continue;
            }
            SpaceRagChunkRef ref = new SpaceRagChunkRef();
            ref.setSpaceId(document.getSpaceId());
            ref.setDocumentId(document.getId());
            ref.setSpaceFileId(document.getSpaceFileId());
            ref.setFileChunkId(fileChunk.getId());
            ref.setStatus(StatusConstant.ENABLE);
            ref.setCreatetime(LocalDateTime.now());
            ref.setUpdatetime(LocalDateTime.now());
            spaceRagChunkRefMapper.insert(ref);
            refCount++;
        }
        qdrantVectorStoreService.upsertSpaceChunks(document.getSpaceId(),document.getSpaceFileId(),document.getId(),fileChunks);
        spaceRagDocumentMapper.updateIndexResult(document.getId(),SpaceConstant.RAG_INDEX_SUCCESS,refCount,null,LocalDateTime.now());
    }

    private List<FileRagChunk> ensureFileChunks(SpaceFile spaceFile, SpaceRagConfig config) {
        File file = fileInfoMapper.getFileByFileUuid(spaceFile.getFileUuid(),spaceFile.getCreatedBy());
        if(file == null || file.getHash() == null || file.getHash().isBlank()) {
            throw new BaseException("文件哈希不存在，无法建立 RAG 切片");
        }
        List<FileRagChunk> exists = fileRagChunkMapper.listByFileUuidAndHash(spaceFile.getFileUuid(),file.getHash());
        if(!exists.isEmpty()) {
            return exists;
        }
        ExtractedDocumentText extracted = documentTextExtractor.extract(spaceFile,file);
        String text = resolveDocumentText(spaceFile,file,extracted);
        int chunkSize = safeChunkSize(config.getChunkSize());
        int chunkOverlap = safeChunkOverlap(config.getChunkOverlap(),config.getChunkSize());
        List<String> contents = splitText(text,chunkSize,chunkOverlap);
        List<FileRagChunk> result = new ArrayList<>();
        int index = 0;
        for(String content : contents) {
            FileRagChunk chunk = new FileRagChunk();
            chunk.setFileUuid(spaceFile.getFileUuid());
            chunk.setFileHash(file.getHash());
            chunk.setChunkIndex(index++);
            chunk.setContent(content);
            chunk.setContentHash(sha256(content));
            chunk.setTokenCount(content.length());
            chunk.setMetadata(buildChunkMetadata(spaceFile,extracted));
            chunk.setVectorId(null);
            chunk.setEmbeddingModel(config.getEmbeddingModel());
            chunk.setChunkSize(chunkSize);
            chunk.setChunkOverlap(chunkOverlap);
            chunk.setStatus(StatusConstant.ENABLE);
            chunk.setCreatetime(LocalDateTime.now());
            chunk.setUpdatetime(LocalDateTime.now());
            fileRagChunkMapper.insert(chunk);
            result.add(chunk);
        }
        return result;
    }

    private String resolveDocumentText(SpaceFile spaceFile, File file, ExtractedDocumentText extracted) {
        if(extracted != null && extracted.isSuccess() && extracted.getText() != null && !extracted.getText().isBlank()) {
            return extracted.getText();
        }
        boolean fallback = ragProperties.getExtraction() == null
                || !Boolean.FALSE.equals(ragProperties.getExtraction().getFallbackToMetadata());
        if(fallback) {
            return buildDocumentText(spaceFile,file);
        }
        String message = extracted == null ? "文档解析失败" : extracted.getErrorMessage();
        throw new BaseException(message == null ? "文档解析失败" : message);
    }

    private String buildChunkMetadata(SpaceFile spaceFile, ExtractedDocumentText extracted) {
        String parser = extracted == null ? "unknown" : extracted.getParser();
        boolean fallback = extracted != null && extracted.isFallback();
        String errorMessage = extracted == null ? null : extracted.getErrorMessage();
        StringBuilder builder = new StringBuilder();
        builder.append("{\"fileName\":\"").append(escapeJson(spaceFile.getFileName())).append("\"");
        builder.append(",\"parser\":\"").append(escapeJson(parser)).append("\"");
        builder.append(",\"fallback\":").append(fallback);
        if(errorMessage != null && !errorMessage.isBlank()) {
            builder.append(",\"errorMessage\":\"").append(escapeJson(truncate(errorMessage,200))).append("\"");
        }
        builder.append("}");
        return builder.toString();
    }

    private SpaceRagDocument createDocument(SpaceFile spaceFile, Long userId) {
        SpaceRagDocument exists = spaceRagDocumentMapper.getBySpaceFileId(spaceFile.getSpaceId(),spaceFile.getId());
        if(exists != null) {
            return exists;
        }
        File file = fileInfoMapper.getFileByFileUuid(spaceFile.getFileUuid(),spaceFile.getCreatedBy());
        LocalDateTime now = LocalDateTime.now();
        SpaceRagDocument document = new SpaceRagDocument();
        document.setSpaceId(spaceFile.getSpaceId());
        document.setSpaceFileId(spaceFile.getId());
        document.setFileUuid(spaceFile.getFileUuid());
        document.setFileName(spaceFile.getFileName());
        document.setFileHash(file == null ? null : file.getHash());
        document.setFileType(file == null ? null : file.getType());
        document.setIndexStatus(SpaceConstant.RAG_INDEX_PENDING);
        document.setChunkCount(0);
        document.setCreatedBy(userId);
        document.setStatus(StatusConstant.ENABLE);
        document.setCreatetime(now);
        document.setUpdatetime(now);
        spaceRagDocumentMapper.insert(document);
        return document;
    }

    private SpaceRagConfig requireConfig(Long spaceId) {
        SpaceRagConfig config = spaceRagMapper.getBySpaceId(spaceId);
        if(config == null) {
            throw new BaseException("空间 RAG 配置不存在");
        }
        return config;
    }

    private SpaceFile requireFile(Long spaceId, Long spaceFileId) {
        SpaceFile spaceFile = spaceFileMapper.getById(spaceId,spaceFileId);
        if(spaceFile == null || spaceFile.getDir() == 1) {
            throw new BaseException("空间文件不存在或不是普通文件");
        }
        return spaceFile;
    }

    private String buildDocumentText(SpaceFile spaceFile, File file) {
        StringBuilder builder = new StringBuilder();
        builder.append("文件名称：").append(spaceFile.getFileName()).append('\n');
        builder.append("空间路径：").append(spaceFile.getPath()).append('\n');
        builder.append("文件 UUID：").append(spaceFile.getFileUuid()).append('\n');
        if(file != null) {
            builder.append("文件类型：").append(file.getType()).append('\n');
            builder.append("文件大小：").append(file.getSize()).append('\n');
            builder.append("文件哈希：").append(file.getHash()).append('\n');
        }
        builder.append("说明：当前索引先保存文件元数据 chunk；接入 MinIO 文件解析和 langchain4j 后会替换为真实文件正文和向量。");
        return builder.toString();
    }

    private List<FileRagChunk> searchChunks(Long spaceId, String question, int limit, SpaceRagConfig config) {
        List<FileRagChunk> spaceChunks = fileRagChunkMapper.listActiveBySpace(spaceId);
        int vectorLimit = Math.max(limit,ragProperties.getVectorTopN() == null ? 30 : ragProperties.getVectorTopN());
        List<FileRagChunk> semanticChunks = qdrantVectorStoreService.search(
                spaceId,
                question,
                spaceChunks,
                vectorLimit,
                config.getScoreThreshold() == null ? null : config.getScoreThreshold().doubleValue()
        );
        if(!semanticChunks.isEmpty()) {
            return ragRerankService.rerank(question,semanticChunks,limit);
        }
        List<FileRagChunk> chunks = fileRagChunkMapper.searchBySpaceAndKeyword(spaceId,question,limit);
        if(!chunks.isEmpty()) {
            return chunks;
        }
        for(String keyword : question.split("\\s+")) {
            if(keyword.isBlank()) {
                continue;
            }
            chunks = fileRagChunkMapper.searchBySpaceAndKeyword(spaceId,keyword,limit);
            if(!chunks.isEmpty()) {
                return chunks;
            }
        }
        return fileRagChunkMapper.listRecentBySpace(spaceId,limit);
    }

    private List<SpaceRagCitationVO> buildCitations(Long spaceId, List<FileRagChunk> chunks) {
        List<SpaceRagCitationVO> citations = new ArrayList<>();
        if(chunks == null || chunks.isEmpty()) {
            return citations;
        }
        for(int i = 0; i < chunks.size(); i++) {
            FileRagChunk chunk = chunks.get(i);
            SpaceRagDocument document = spaceRagDocumentMapper.getBySpaceAndChunkId(spaceId,chunk.getId());
            SpaceRagCitationVO citation = new SpaceRagCitationVO();
            citation.setIndex(i + 1);
            citation.setChunkId(chunk.getId());
            citation.setContentSummary(truncate(chunk.getContent(),240));
            if(document != null) {
                citation.setDocumentId(document.getId());
                citation.setSpaceFileId(document.getSpaceFileId());
                citation.setFileName(document.getFileName());
                String base = "/api/space/" + spaceId + "/files/" + document.getSpaceFileId();
                citation.setPreviewUrl(base + "/preview");
                citation.setDownloadUrl(base + "/download");
            }
            citations.add(citation);
        }
        return citations;
    }

    private List<String> splitText(String text, int chunkSize, int chunkOverlap) {
        List<String> chunks = new ArrayList<>();
        if(text == null || text.isBlank()) {
            return chunks;
        }
        int start = 0;
        while(start < text.length()) {
            int end = Math.min(start + chunkSize,text.length());
            chunks.add(text.substring(start,end));
            if(end == text.length()) {
                break;
            }
            start = Math.max(end - chunkOverlap,start + 1);
        }
        return chunks;
    }

    private SpaceRagTask createTask(Long spaceId, Long spaceFileId, Long documentId, String taskType, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        SpaceRagTask task = new SpaceRagTask();
        task.setSpaceId(spaceId);
        task.setSpaceFileId(spaceFileId);
        task.setDocumentId(documentId);
        task.setTaskType(taskType);
        task.setTaskStatus(SpaceConstant.RAG_TASK_PENDING);
        task.setCreatedBy(userId);
        task.setCreatetime(now);
        task.setUpdatetime(now);
        spaceRagTaskMapper.insert(task);
        return task;
    }

    private void finishTask(SpaceRagTask task, String status, String errorMessage, LocalDateTime startedTime) {
        spaceRagTaskMapper.updateResult(task.getId(),status,errorMessage,startedTime,LocalDateTime.now(),LocalDateTime.now());
    }

    private void finishTask(Long taskId, String status, String errorMessage, LocalDateTime startedTime) {
        spaceRagTaskMapper.updateResult(taskId,status,errorMessage,startedTime,LocalDateTime.now(),LocalDateTime.now());
    }

    private void markTaskRunning(Long taskId, LocalDateTime startedTime) {
        spaceRagTaskMapper.updateResult(taskId,SpaceConstant.RAG_TASK_RUNNING,null,startedTime,null,LocalDateTime.now());
    }

    private void dispatchAfterCommit(Runnable runnable) {
        if(TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runnable.run();
                }
            });
            return;
        }
        runnable.run();
    }

    private void saveQueryLog(Long spaceId, Long userId, String question, String answer, String hitChunkIds, String modelName, boolean success, String errorMessage) {
        SpaceRagQueryLog log = new SpaceRagQueryLog();
        log.setSpaceId(spaceId);
        log.setUserId(userId);
        log.setQuestion(question);
        log.setAnswer(answer);
        log.setHitChunkIds(hitChunkIds);
        log.setModelName(modelName);
        log.setPromptTokens(question == null ? 0 : question.length());
        log.setCompletionTokens(answer == null ? 0 : answer.length());
        log.setTotalTokens(log.getPromptTokens() + log.getCompletionTokens());
        log.setSuccess(success ? StatusConstant.ENABLE : StatusConstant.DISABLE);
        log.setErrorMessage(errorMessage);
        log.setCreatetime(LocalDateTime.now());
        spaceRagQueryLogMapper.insert(log);
    }

    private SpaceRagConfigVO toConfigVO(SpaceRagConfig config) {
        SpaceRagConfigVO vo = new SpaceRagConfigVO();
        vo.setId(config.getId());
        vo.setSpaceId(config.getSpaceId());
        vo.setEmbeddingModel(config.getEmbeddingModel());
        vo.setChatModel(config.getChatModel());
        vo.setVectorCollection(config.getVectorCollection());
        vo.setChunkSize(config.getChunkSize());
        vo.setChunkOverlap(config.getChunkOverlap());
        vo.setTopK(config.getTopK());
        vo.setScoreThreshold(config.getScoreThreshold());
        vo.setEnabled(config.getEnabled());
        vo.setStatus(config.getStatus());
        vo.setCreatetime(config.getCreatetime());
        vo.setUpdatetime(config.getUpdatetime());
        return vo;
    }

    private SpaceRagDocumentVO toDocumentVO(SpaceRagDocument document) {
        SpaceRagDocumentVO vo = new SpaceRagDocumentVO();
        vo.setId(document.getId());
        vo.setSpaceId(document.getSpaceId());
        vo.setSpaceFileId(document.getSpaceFileId());
        vo.setFileUuid(document.getFileUuid());
        vo.setFileName(document.getFileName());
        vo.setFileHash(document.getFileHash());
        vo.setFileType(document.getFileType());
        vo.setIndexStatus(document.getIndexStatus());
        vo.setChunkCount(document.getChunkCount());
        vo.setErrorMessage(document.getErrorMessage());
        vo.setCreatetime(document.getCreatetime());
        vo.setUpdatetime(document.getUpdatetime());
        return vo;
    }

    private SpaceRagTaskVO toTaskVO(SpaceRagTask task) {
        SpaceRagTaskVO vo = new SpaceRagTaskVO();
        vo.setId(task.getId());
        vo.setSpaceId(task.getSpaceId());
        vo.setSpaceFileId(task.getSpaceFileId());
        vo.setDocumentId(task.getDocumentId());
        vo.setTaskType(task.getTaskType());
        vo.setTaskStatus(task.getTaskStatus());
        vo.setErrorMessage(task.getErrorMessage());
        vo.setCreatedBy(task.getCreatedBy());
        vo.setStartedTime(task.getStartedTime());
        vo.setFinishedTime(task.getFinishedTime());
        vo.setCreatetime(task.getCreatetime());
        vo.setUpdatetime(task.getUpdatetime());
        return vo;
    }

    private void fillDocumentLinks(Long spaceId, SpaceDocumentSearchVO vo) {
        String base = "/api/space/" + spaceId + "/files/" + vo.getSpaceFileId();
        vo.setPreviewUrl(base + "/preview");
        vo.setStreamUrl(base + "/preview/stream");
        vo.setDownloadUrl(base + "/download");
    }

    private int safeChunkSize(Integer chunkSize) {
        return chunkSize == null || chunkSize <= 0 ? DEFAULT_CHUNK_SIZE : chunkSize;
    }

    private int safeChunkOverlap(Integer chunkOverlap, Integer chunkSize) {
        int realChunkSize = safeChunkSize(chunkSize);
        if(chunkOverlap == null || chunkOverlap < 0) {
            return DEFAULT_CHUNK_OVERLAP;
        }
        return Math.min(chunkOverlap,realChunkSize - 1);
    }

    private int safeTopK(Integer topK) {
        return topK == null || topK <= 0 ? DEFAULT_TOP_K : topK;
    }

    private String blankToCurrent(String value, String current) {
        return value == null || value.isBlank() ? current : value;
    }

    private String truncate(String value, int maxLength) {
        if(value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0,maxLength);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encoded = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for(byte b : encoded) {
                builder.append(String.format("%02x",b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new BaseException("计算文本块哈希失败");
        }
    }

    private String escapeJson(String value) {
        if(value == null) {
            return "";
        }
        return value.replace("\\","\\\\").replace("\"","\\\"");
    }
}
