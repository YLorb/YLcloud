package com.ylcloud.service;

import com.ylcloud.DTO.SpaceDocumentSearchDTO;
import com.ylcloud.DTO.SpaceRagConfigUpdateDTO;
import com.ylcloud.DTO.SpaceRagQueryDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.VO.SpaceDocumentChunkHitVO;
import com.ylcloud.VO.SpaceDocumentSearchVO;
import com.ylcloud.VO.SpaceKnowledgePipelineTaskVO;
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
import com.ylcloud.entity.SpaceRagConfigLog;
import com.ylcloud.entity.SpaceRagDocument;
import com.ylcloud.entity.SpaceRagQueryLog;
import com.ylcloud.entity.SpaceRagTask;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.FileRagChunkMapper;
import com.ylcloud.mapper.SpaceFileMapper;
import com.ylcloud.mapper.SpaceRagChunkRefMapper;
import com.ylcloud.mapper.SpaceRagConfigLogMapper;
import com.ylcloud.mapper.SpaceRagDocumentMapper;
import com.ylcloud.mapper.SpaceRagMapper;
import com.ylcloud.mapper.SpaceRagQueryLogMapper;
import com.ylcloud.mapper.SpaceRagTaskMapper;
import com.ylcloud.service.rag.QdrantVectorStoreService;
import com.ylcloud.service.rag.ExtractedDocumentText;
import com.ylcloud.service.rag.RagChatResult;
import com.ylcloud.service.rag.RagChatService;
import com.ylcloud.service.rag.RagRerankService;
import com.ylcloud.service.rag.RagTaskExecutorService;
import com.ylcloud.service.rag.parser.DocumentParser;
import com.ylcloud.service.rag.parser.ParsedDocument;
import com.ylcloud.service.rag.parser.StructuredChunk;
import com.ylcloud.service.rag.parser.StructuredChunker;
import com.ylcloud.service.rag.query.QueryPlan;
import com.ylcloud.service.rag.query.QueryRewriteService;
import com.ylcloud.service.rag.retriever.RagMultiRouteRetriever;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 空间 RAG 业务服务。
 */
@Service
public class SpaceRagService {
    private static final Logger log = LoggerFactory.getLogger(SpaceRagService.class);
    private static final int DEFAULT_CHUNK_SIZE = 1000;
    private static final int DEFAULT_CHUNK_OVERLAP = 100;
    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 20;

    private final SpaceRagMapper spaceRagMapper;
    private final SpaceRagDocumentMapper spaceRagDocumentMapper;
    private final FileRagChunkMapper fileRagChunkMapper;
    private final SpaceRagChunkRefMapper spaceRagChunkRefMapper;
    private final SpaceRagTaskMapper spaceRagTaskMapper;
    private final SpaceRagQueryLogMapper spaceRagQueryLogMapper;
    private final SpaceRagConfigLogMapper spaceRagConfigLogMapper;
    private final SpaceFileMapper spaceFileMapper;
    private final FileInfoMapper fileInfoMapper;
    private final SpacePermissionService spacePermissionService;
    private final QdrantVectorStoreService qdrantVectorStoreService;
    private final RagChatService ragChatService;
    private final RagRerankService ragRerankService;
    private final RagMultiRouteRetriever ragMultiRouteRetriever;
    private final RagProperties ragProperties;
    private final DocumentParser documentParser;
    private final StructuredChunker structuredChunker;
    private final QueryRewriteService queryRewriteService;
    private final RagTaskExecutorService ragTaskExecutorService;
    private final KnowledgePipelineService knowledgePipelineService;
    private final KnowledgePipelineExecutorService knowledgePipelineExecutorService;
    private final SiteSettingService siteSettingService;

    /**
     * 初始化 SpaceRagService 对象。
     *
     * @param spaceRagMapper 方法入参
     * @param spaceRagDocumentMapper 方法入参
     * @param fileRagChunkMapper 方法入参
     * @param spaceRagChunkRefMapper 方法入参
     * @param spaceRagTaskMapper 方法入参
     * @param spaceRagQueryLogMapper 方法入参
     * @param spaceFileMapper 方法入参
     * @param fileInfoMapper 方法入参
     * @param spacePermissionService 方法入参
     * @param qdrantVectorStoreService 方法入参
     * @param ragChatService 方法入参
     * @param ragRerankService 方法入参
     * @param ragMultiRouteRetriever 方法入参
     * @param ragProperties RAG 配置属性
     * @param documentParser 方法入参
     * @param structuredChunker 方法入参
     * @param ragTaskExecutorService 方法入参
     */
    public SpaceRagService(SpaceRagMapper spaceRagMapper,
                           SpaceRagDocumentMapper spaceRagDocumentMapper,
                           FileRagChunkMapper fileRagChunkMapper,
                           SpaceRagChunkRefMapper spaceRagChunkRefMapper,
                           SpaceRagTaskMapper spaceRagTaskMapper,
                           SpaceRagQueryLogMapper spaceRagQueryLogMapper,
                           SpaceRagConfigLogMapper spaceRagConfigLogMapper,
                           SpaceFileMapper spaceFileMapper,
                           FileInfoMapper fileInfoMapper,
                           SpacePermissionService spacePermissionService,
                           QdrantVectorStoreService qdrantVectorStoreService,
                           RagChatService ragChatService,
                           RagRerankService ragRerankService,
                           RagMultiRouteRetriever ragMultiRouteRetriever,
                           RagProperties ragProperties,
                           DocumentParser documentParser,
                           StructuredChunker structuredChunker,
                           QueryRewriteService queryRewriteService,
                           RagTaskExecutorService ragTaskExecutorService,
                           KnowledgePipelineService knowledgePipelineService,
                           KnowledgePipelineExecutorService knowledgePipelineExecutorService,
                           SiteSettingService siteSettingService) {
        this.spaceRagMapper = spaceRagMapper;
        this.spaceRagDocumentMapper = spaceRagDocumentMapper;
        this.fileRagChunkMapper = fileRagChunkMapper;
        this.spaceRagChunkRefMapper = spaceRagChunkRefMapper;
        this.spaceRagTaskMapper = spaceRagTaskMapper;
        this.spaceRagQueryLogMapper = spaceRagQueryLogMapper;
        this.spaceRagConfigLogMapper = spaceRagConfigLogMapper;
        this.spaceFileMapper = spaceFileMapper;
        this.fileInfoMapper = fileInfoMapper;
        this.spacePermissionService = spacePermissionService;
        this.qdrantVectorStoreService = qdrantVectorStoreService;
        this.ragChatService = ragChatService;
        this.ragRerankService = ragRerankService;
        this.ragMultiRouteRetriever = ragMultiRouteRetriever;
        this.ragProperties = ragProperties;
        this.documentParser = documentParser;
        this.structuredChunker = structuredChunker;
        this.queryRewriteService = queryRewriteService;
        this.ragTaskExecutorService = ragTaskExecutorService;
        this.knowledgePipelineService = knowledgePipelineService;
        this.knowledgePipelineExecutorService = knowledgePipelineExecutorService;
        this.siteSettingService = siteSettingService;
    }

    /**
     * 查询 getConfig 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param userId 用户 ID
     * @return 处理结果
     */
    public SpaceRagConfigVO getConfig(Long spaceId, Long userId) {
        spacePermissionService.requireMember(spaceId,userId);
        return toConfigVO(requireConfig(spaceId));
    }

    /**
     * 更新 updateConfig 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param dto 请求参数
     * @param userId 用户 ID
     * @return 处理结果
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
        Integer topK = safeConfiguredTopK(dto.getTopK() == null ? current.getTopK() : dto.getTopK());
        BigDecimal temperature = safeTemperature(dto.getTemperature() == null ? current.getTemperature() : dto.getTemperature());
        int rows = spaceRagMapper.updateConfig(
                spaceId,
                blankToCurrent(dto.getEmbeddingModel(),current.getEmbeddingModel()),
                blankToCurrent(dto.getChatModel(),current.getChatModel()),
                chunkSize,
                chunkOverlap,
                topK,
                temperature,
                dto.getScoreThreshold() == null ? current.getScoreThreshold() : dto.getScoreThreshold(),
                dto.getEnabled() == null ? current.getEnabled() : dto.getEnabled(),
                LocalDateTime.now()
        );
        if(rows == 0) {
            throw new BaseException("空间 RAG 配置更新失败");
        }
        SpaceRagConfig updated = requireConfig(spaceId);
        saveConfigChangeLog(spaceId,userId,current,updated);
        return toConfigVO(updated);
    }

    /**
     * 执行 query 函数的业务处理。
     *
     * @param spaceId 空间 ID
     * @param dto 请求参数
     * @param userId 用户 ID
     * @return 处理结果
     */
    @Transactional
    public SpaceRagQueryVO query(Long spaceId, SpaceRagQueryDTO dto, Long userId) {
        spacePermissionService.requireMember(spaceId,userId);
        if(!Boolean.TRUE.equals(siteSettingService.getBoolean(SiteSettingService.LLM_ENABLED,true))) {
            throw new BaseException("管理员已停用 AI 问答");
        }
        SpaceRagConfig config = requireConfig(spaceId);
        if(!StatusConstant.ENABLE.equals(config.getEnabled())) {
            throw new BaseException("当前空间未启用 RAG");
        }
        int limit = resolveQueryTopK(config,dto.getRetrievalMode());
        QueryPlan queryPlan = queryRewriteService.plan(dto.getQuestion(),dto.getHistory());
        List<FileRagChunk> chunks = searchChunks(spaceId,queryPlan,limit,config);
        RagChatResult chatResult = ragChatService.answer(dto.getQuestion(),chunks,config);
        String answer = chatResult.getAnswer();
        boolean noAnswer = answer == null || answer.isBlank() || answer.contains("无法从当前知识库回答");
        List<Long> hitChunkIds = new ArrayList<>();
        List<String> contexts = new ArrayList<>();
        StringJoiner idJoiner = new StringJoiner(",");
        if(!noAnswer) {
            for(FileRagChunk chunk : chunks) {
                hitChunkIds.add(chunk.getId());
                contexts.add(chunk.getContent());
                idJoiner.add(String.valueOf(chunk.getId()));
            }
        }
        saveQueryLog(spaceId,userId,dto.getQuestion(),answer,idJoiner.toString(),
                blankToCurrent(chatResult.getModelName(),config.getChatModel()),limit,safeTemperature(config.getTemperature()),
                chatResult.isSuccess(),chatResult.getErrorMessage());

        SpaceRagQueryVO vo = new SpaceRagQueryVO();
        vo.setSpaceId(spaceId);
        vo.setQuestion(dto.getQuestion());
        vo.setAnswer(answer);
        vo.setHitChunkIds(hitChunkIds);
        vo.setContexts(contexts);
        vo.setCitations(noAnswer ? new ArrayList<>() : buildCitations(spaceId,chunks));
        return vo;
    }

    /**
     * 查询 listDocuments 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param userId 用户 ID
     * @return 列表结果
     */
    public List<SpaceRagDocumentVO> listDocuments(Long spaceId, Long userId) {
        spacePermissionService.requireAdmin(spaceId,userId);
        expireStaleIndexTasks(spaceId);
        List<SpaceRagDocumentVO> result = new ArrayList<>();
        for(SpaceRagDocument document : spaceRagDocumentMapper.listBySpaceId(spaceId)) {
            result.add(toDocumentVO(document));
        }
        return result;
    }

    /**
     * 查询 listTasks 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param userId 用户 ID
     * @return 列表结果
     */
    public List<SpaceRagTaskVO> listTasks(Long spaceId, Long userId) {
        spacePermissionService.requireAdmin(spaceId,userId);
        expireStaleIndexTasks(spaceId);
        List<SpaceRagTaskVO> result = new ArrayList<>();
        for(SpaceRagTask task : spaceRagTaskMapper.listBySpaceId(spaceId)) {
            result.add(toTaskVO(task));
        }
        return result;
    }

    /**
     * 重试 retryTask 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param taskId 任务 ID
     * @param userId 用户 ID
     * @return 处理结果
     */
    @Transactional
    public Boolean retryTask(Long spaceId, Long taskId, Long userId) {
        spacePermissionService.requireAdmin(spaceId,userId);
        SpaceRagTask failedTask = spaceRagTaskMapper.getById(taskId);
        if(failedTask == null || !spaceId.equals(failedTask.getSpaceId())) {
            throw new BaseException("RAG 任务不存在");
        }
        if(!SpaceConstant.RAG_TASK_FAILED.equals(failedTask.getTaskStatus())) {
            throw new BaseException("只有失败的 RAG 任务可以重试");
        }
        if(SpaceConstant.RAG_TASK_REBUILD_SPACE.equals(failedTask.getTaskType())) {
            return rebuildSpace(spaceId,userId);
        }
        if(failedTask.getSpaceFileId() != null) {
            return rebuildFile(spaceId,failedTask.getSpaceFileId(),userId);
        }
        throw new BaseException("当前 RAG 任务缺少可重试的文件范围");
    }

    /**
     * 重试 retryFailedTasks 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param userId 用户 ID
     * @return 处理结果
     */
    @Transactional
    public Boolean retryFailedTasks(Long spaceId, Long userId) {
        return repairSpaceVectors(spaceId,userId);
    }

    /**
     * 修复 repairSpaceVectors 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param userId 用户 ID
     * @return 处理结果
     */
    @Transactional
    public Boolean repairSpaceVectors(Long spaceId, Long userId) {
        spacePermissionService.requireAdmin(spaceId,userId);
        expireStaleIndexTasks(spaceId);
        SpaceRagTask runningTask = spaceRagTaskMapper.findRunningSpaceTask(spaceId,SpaceConstant.RAG_TASK_REBUILD_SPACE);
        if(runningTask != null) {
            return true;
        }
        SpaceRagTask task = createTask(spaceId,null,null,SpaceConstant.RAG_TASK_REBUILD_SPACE,userId);
        dispatchAfterCommit(() -> ragTaskExecutorService.runSpaceRepairTask(task.getId(),spaceId,userId));
        return true;
    }

    /**
     * 修复 repairFileVectors 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param spaceFileId 空间文件 ID
     * @param userId 用户 ID
     * @return 处理结果
     */
    @Transactional
    public Boolean repairFileVectors(Long spaceId, Long spaceFileId, Long userId) {
        spacePermissionService.requireAdmin(spaceId,userId);
        return rebuildFile(spaceId,spaceFileId,userId);
    }

    /**
     * 搜索 searchDocuments 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param dto 请求参数
     * @param userId 用户 ID
     * @return 列表结果
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
     * 重建 rebuildSpace 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param userId 用户 ID
     * @return 处理结果
     */
    @Transactional
    public Boolean rebuildSpace(Long spaceId, Long userId) {
        spacePermissionService.requireAdmin(spaceId,userId);
        expireStaleIndexTasks(spaceId);
        SpaceRagTask runningTask = spaceRagTaskMapper.findRunningSpaceTask(spaceId,SpaceConstant.RAG_TASK_REBUILD_SPACE);
        if(runningTask != null) {
            return true;
        }
        SpaceRagTask task = createTask(spaceId,null,null,SpaceConstant.RAG_TASK_REBUILD_SPACE,userId);
        dispatchAfterCommit(() -> ragTaskExecutorService.runSpaceTask(task.getId(),spaceId,userId));
        return true;
    }

    /**
     * 重建 rebuildFile 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param spaceFileId 空间文件 ID
     * @param userId 用户 ID
     * @return 处理结果
     */
    @Transactional
    public Boolean rebuildFile(Long spaceId, Long spaceFileId, Long userId) {
        spacePermissionService.requireAdmin(spaceId,userId);
        expireStaleIndexTasks(spaceId);
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
     * 执行 handleFileImported 函数的业务处理。
     *
     * @param spaceFile 空间文件对象
     * @param userId 用户 ID
     */
    @Transactional
    public void handleFileImported(SpaceFile spaceFile, Long userId) {
        if(spaceFile == null || spaceFile.getDir() == 1) {
            return;
        }
        expireStaleIndexTasks(spaceFile.getSpaceId());
        SpaceRagDocument document = createDocument(spaceFile,userId);
        SpaceRagTask runningTask = spaceRagTaskMapper.findRunningFileTask(spaceFile.getSpaceId(),spaceFile.getId());
        if(runningTask != null) {
            return;
        }
        SpaceRagTask task = createTask(spaceFile.getSpaceId(),spaceFile.getId(),document.getId(),SpaceConstant.RAG_TASK_INDEX_FILE,userId);
        dispatchAfterCommit(() -> ragTaskExecutorService.runFileTask(task.getId(),document.getId(),userId));
    }

    /**
     * 执行 executeFileRagTask 相关逻辑。
     *
     * @param taskId 任务 ID
     * @param documentId 文档 ID
     * @param userId 用户 ID
     */
    public void executeFileRagTask(Long taskId, Long documentId, Long userId) {
        LocalDateTime started = LocalDateTime.now();
        try {
            markTaskRunning(taskId,started);
            updateTaskProgress(taskId,1,0,0);
            SpaceRagDocument document = spaceRagDocumentMapper.getById(documentId);
            if(document == null) {
                throw new BaseException("RAG document not found");
            }
            rebuildDocument(document,userId);
            updateTaskProgress(taskId,1,1,0);
            finishTask(taskId,SpaceConstant.RAG_TASK_SUCCESS,null,started);
        } catch (Throwable ex) {
            String errorMessage = truncate(ex.getMessage(),1000);
            updateTaskProgress(taskId,1,0,1);
            finishTask(taskId,SpaceConstant.RAG_TASK_FAILED,errorMessage,started);
            if(documentId != null) {
                spaceRagDocumentMapper.updateIndexResult(documentId,SpaceConstant.RAG_INDEX_FAILED,0,errorMessage,LocalDateTime.now());
            }
        }
    }

    /**
     * 执行 executeSpaceRagTask 相关逻辑。
     *
     * @param taskId 任务 ID
     * @param spaceId 空间 ID
     * @param userId 用户 ID
     */
    public void executeSpaceRagTask(Long taskId, Long spaceId, Long userId) {
        executeSpaceRagTask(taskId,spaceId,userId,false);
    }

    /**
     * 执行 executeSpaceRagTask 相关逻辑。
     *
     * @param taskId 任务 ID
     * @param spaceId 空间 ID
     * @param userId 用户 ID
     * @param clearSpaceVectors 是否清理空间向量
     */
    public void executeSpaceRagTask(Long taskId, Long spaceId, Long userId, boolean clearSpaceVectors) {
        LocalDateTime started = LocalDateTime.now();
        try {
            markTaskRunning(taskId,started);
            if(clearSpaceVectors) {
                qdrantVectorStoreService.deleteBySpaceStrict(spaceId);
            }
            List<SpaceRagDocument> documents = spaceRagDocumentMapper.listBySpaceId(spaceId);
            updateTaskProgress(taskId,documents.size(),0,0);
            AtomicInteger failed = new AtomicInteger();
            AtomicInteger success = new AtomicInteger();
            ExecutorService executor = Executors.newFixedThreadPool(indexConcurrency());
            try {
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                for(SpaceRagDocument document : documents) {
                    futures.add(CompletableFuture.runAsync(() -> {
                        try {
                            rebuildDocument(document,userId);
                            success.incrementAndGet();
                        } catch (Throwable ex) {
                            failed.incrementAndGet();
                            spaceRagDocumentMapper.updateIndexResult(
                                    document.getId(),
                                    SpaceConstant.RAG_INDEX_FAILED,
                                    0,
                                    truncate(ex.getMessage(),1000),
                                    LocalDateTime.now()
                            );
                        } finally {
                            updateTaskProgress(taskId,documents.size(),success.get(),failed.get());
                        }
                    },executor));
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            } finally {
                executor.shutdown();
            }
            if(failed.get() > 0) {
                finishTask(taskId,SpaceConstant.RAG_TASK_FAILED,"Partial document indexing failed: " + failed.get() + "/" + documents.size(),started);
                return;
            }
            finishTask(taskId,SpaceConstant.RAG_TASK_SUCCESS,null,started);
        } catch (Throwable ex) {
            finishTask(taskId,SpaceConstant.RAG_TASK_FAILED,truncate(ex.getMessage(),1000),started);
        }
    }

    /**
     * 执行 handleFileRemoved 函数的业务处理。
     *
     * @param spaceId 空间 ID
     * @param spaceFileId 空间文件 ID
     * @param userId 用户 ID
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

    /**
     * 重建 rebuildDocument 相关逻辑。
     *
     * @param document 文档对象
     * @param userId 用户 ID
     */
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
        submitKnowledgeProfileTask(document.getSpaceId(),document.getId(),userId);
    }

    private void submitKnowledgeProfileTask(Long spaceId, Long documentId, Long userId) {
        try {
            SpaceKnowledgePipelineTaskVO task = knowledgePipelineService.submitDocumentProfileTaskIfAbsent(spaceId,documentId,userId);
            if(task == null) {
                log.info("Knowledge pipeline task skipped because an active task exists: spaceId={}, documentId={}",spaceId,documentId);
                return;
            }
            knowledgePipelineExecutorService.runDocumentTask(task.getId());
            log.info("Knowledge pipeline task submitted: spaceId={}, documentId={}, taskId={}",spaceId,documentId,task.getId());
        } catch (Exception ex) {
            log.warn("Knowledge pipeline task submit failed: spaceId={}, documentId={}",spaceId,documentId,ex);
        }
    }

    /**
     * 确保 ensureFileChunks 相关逻辑。
     *
     * @param spaceFile 空间文件对象
     * @param config 配置对象
     * @return 列表结果
     */
    private List<FileRagChunk> ensureFileChunks(SpaceFile spaceFile, SpaceRagConfig config) {
        File file = fileInfoMapper.getFileByFileUuid(spaceFile.getFileUuid(),spaceFile.getCreatedBy());
        if(file == null || file.getHash() == null || file.getHash().isBlank()) {
            throw new BaseException("文件哈希不存在，无法建立 RAG 切片");
        }
        List<FileRagChunk> exists = fileRagChunkMapper.listByFileUuidAndHash(spaceFile.getFileUuid(),file.getHash());
        if(!exists.isEmpty() && !isMetadataFallbackChunks(exists)) {
            return exists;
        }
        if(!exists.isEmpty()) {
            fileRagChunkMapper.disableByFileUuidAndHash(spaceFile.getFileUuid(),file.getHash());
        }
        ParsedDocument parsed = documentParser.parse(spaceFile,file);
        if(parsed == null || !parsed.isSuccess()) {
            String message = parsed == null ? "文档解析失败" : parsed.getErrorMessage();
            throw new BaseException(message == null ? "文档解析失败" : message);
        }
        int chunkSize = safeChunkSize(config.getChunkSize());
        int chunkOverlap = safeChunkOverlap(config.getChunkOverlap(),config.getChunkSize());
        List<StructuredChunk> chunks = structuredChunker.chunk(parsed,chunkSize,chunkOverlap);
        List<FileRagChunk> result = new ArrayList<>();
        for(StructuredChunk structuredChunk : chunks) {
            FileRagChunk chunk = new FileRagChunk();
            chunk.setFileUuid(spaceFile.getFileUuid());
            chunk.setFileHash(file.getHash());
            chunk.setChunkIndex(structuredChunk.getChunkIndex());
            chunk.setContent(structuredChunk.getContent());
            chunk.setContentHash(structuredChunk.getContentHash());
            chunk.setTokenCount(structuredChunk.getTokenCount());
            chunk.setMetadata(structuredChunk.getMetadataJson());
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

    /**
     * 解析 resolveDocumentText 相关逻辑。
     *
     * @param spaceFile 空间文件对象
     * @param file 文件对象
     * @param extracted 方法入参
     * @return 处理结果
     */
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

    /**
     * 构建 buildChunkMetadata 相关逻辑。
     *
     * @param spaceFile 空间文件对象
     * @param extracted 方法入参
     * @return 处理结果
     */
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

    /**
     * 创建 createDocument 相关逻辑。
     *
     * @param spaceFile 空间文件对象
     * @param userId 用户 ID
     * @return 处理结果
     */
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

    /**
     * 校验 requireConfig 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @return 处理结果
     */
    private SpaceRagConfig requireConfig(Long spaceId) {
        SpaceRagConfig config = spaceRagMapper.getBySpaceId(spaceId);
        if(config == null) {
            throw new BaseException("空间 RAG 配置不存在");
        }
        return config;
    }

    /**
     * 校验 requireFile 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param spaceFileId 空间文件 ID
     * @return 处理结果
     */
    private SpaceFile requireFile(Long spaceId, Long spaceFileId) {
        SpaceFile spaceFile = spaceFileMapper.getById(spaceId,spaceFileId);
        if(spaceFile == null || spaceFile.getDir() == 1) {
            throw new BaseException("空间文件不存在或不是普通文件");
        }
        return spaceFile;
    }

    /**
     * 构建 buildDocumentText 相关逻辑。
     *
     * @param spaceFile 空间文件对象
     * @param file 文件对象
     * @return 处理结果
     */
    private boolean isMetadataFallbackChunks(List<FileRagChunk> chunks) {
        if(chunks == null || chunks.isEmpty()) {
            return false;
        }
        for(FileRagChunk chunk : chunks) {
            String metadata = chunk.getMetadata();
            if(metadata == null || !metadata.contains("\"parser\":\"metadata\"") || !metadata.contains("\"fallback\":true")) {
                return false;
            }
        }
        return true;
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

    /**
     * 搜索 searchChunks 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param question 问题内容
     * @param limit 限制数量
     * @param config 配置对象
     * @return 列表结果
     */
    private List<FileRagChunk> searchChunks(Long spaceId, QueryPlan queryPlan, int limit, SpaceRagConfig config) {
        List<FileRagChunk> spaceChunks = fileRagChunkMapper.listActiveBySpace(spaceId);
        List<FileRagChunk> candidates = ragMultiRouteRetriever.retrieve(
                spaceId,
                queryPlan,
                spaceChunks,
                limit,
                config.getScoreThreshold() == null ? null : config.getScoreThreshold().doubleValue()
        );
        if(!candidates.isEmpty()) {
            int candidateTopK = rerankCandidateTopK();
            List<FileRagChunk> expandedCandidates = expandContextChunks(candidates,spaceChunks,queryPlan,candidateTopK);
            List<FileRagChunk> rerankCandidates = limitChunks(expandedCandidates,candidateTopK);
            return ragRerankService.rerank(queryPlan.getOriginal(),rerankCandidates,Math.min(limit,candidateTopK));
        }
        return List.of();
    }

    private List<FileRagChunk> expandContextChunks(List<FileRagChunk> candidates,
                                                   List<FileRagChunk> spaceChunks,
                                                   QueryPlan queryPlan,
                                                   int limit) {
        if(candidates == null || candidates.isEmpty() || spaceChunks == null || spaceChunks.isEmpty()) {
            return candidates == null ? List.of() : candidates;
        }
        int max = Math.max(limit * 3,limit + 8);
        Map<Long, FileRagChunk> selected = new LinkedHashMap<>();
        Map<String, FileRagChunk> byPosition = new LinkedHashMap<>();
        for(FileRagChunk chunk : spaceChunks) {
            if(chunk.getId() != null && chunk.getFileUuid() != null && chunk.getFileHash() != null && chunk.getChunkIndex() != null) {
                byPosition.put(positionKey(chunk.getFileUuid(),chunk.getFileHash(),chunk.getChunkIndex()),chunk);
            }
        }
        boolean codeIntent = queryPlan != null && ("code".equals(queryPlan.getIntent()) || looksLikeCodeQuestion(queryPlan.getOriginal()));
        for(FileRagChunk chunk : candidates) {
            addChunk(selected,chunk,max);
            Integer parentIndex = metadataInt(chunk.getMetadata(),"parentChunkIndex");
            if(parentIndex != null) {
                FileRagChunk parent = byPosition.get(positionKey(chunk.getFileUuid(),chunk.getFileHash(),parentIndex));
                addChunk(selected,parent,max);
                if(codeIntent) {
                    addNeighborChunks(selected,byPosition,parent == null ? chunk : parent,1,max);
                }
            } else if(codeIntent && "parent".equals(metadataString(chunk.getMetadata(),"chunkType"))) {
                addNeighborChunks(selected,byPosition,chunk,1,max);
            }
            if(codeIntent) {
                addNeighborChunks(selected,byPosition,chunk,1,max);
            }
            if(selected.size() >= max) {
                break;
            }
        }
        return selected.values().stream()
                .sorted(Comparator.comparing(FileRagChunk::getFileUuid,Comparator.nullsLast(String::compareTo))
                        .thenComparing(FileRagChunk::getFileHash,Comparator.nullsLast(String::compareTo))
                        .thenComparing(FileRagChunk::getChunkIndex,Comparator.nullsLast(Integer::compareTo)))
                .toList();
    }

    private void addNeighborChunks(Map<Long, FileRagChunk> selected,
                                   Map<String, FileRagChunk> byPosition,
                                   FileRagChunk chunk,
                                   int radius,
                                   int max) {
        if(chunk == null || chunk.getFileUuid() == null || chunk.getFileHash() == null || chunk.getChunkIndex() == null) {
            return;
        }
        for(int offset = -radius; offset <= radius; offset++) {
            if(offset == 0 || selected.size() >= max) {
                continue;
            }
            FileRagChunk neighbor = byPosition.get(positionKey(chunk.getFileUuid(),chunk.getFileHash(),chunk.getChunkIndex() + offset));
            addChunk(selected,neighbor,max);
        }
    }

    private void addChunk(Map<Long, FileRagChunk> selected, FileRagChunk chunk, int max) {
        if(chunk == null || chunk.getId() == null || selected.size() >= max) {
            return;
        }
        selected.putIfAbsent(chunk.getId(),chunk);
    }

    private String positionKey(String fileUuid, String fileHash, Integer chunkIndex) {
        return fileUuid + "|" + fileHash + "|" + chunkIndex;
    }

    private boolean looksLikeCodeQuestion(String question) {
        if(question == null) {
            return false;
        }
        return question.contains("代码") || question.contains("怎么写") || question.toLowerCase().contains("code");
    }

    private Integer metadataInt(String metadata, String key) {
        String value = metadataString(metadata,key);
        if(value == null || value.isBlank() || "null".equals(value)) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String metadataString(String metadata, String key) {
        if(metadata == null || metadata.isBlank() || key == null || key.isBlank()) {
            return null;
        }
        String marker = "\"" + key + "\":";
        int start = metadata.indexOf(marker);
        if(start < 0) {
            return null;
        }
        int valueStart = start + marker.length();
        while(valueStart < metadata.length() && Character.isWhitespace(metadata.charAt(valueStart))) {
            valueStart++;
        }
        if(valueStart >= metadata.length()) {
            return null;
        }
        if(metadata.charAt(valueStart) == '\"') {
            int valueEnd = metadata.indexOf('\"',valueStart + 1);
            return valueEnd < 0 ? null : metadata.substring(valueStart + 1,valueEnd);
        }
        int valueEnd = valueStart;
        while(valueEnd < metadata.length() && metadata.charAt(valueEnd) != ',' && metadata.charAt(valueEnd) != '}') {
            valueEnd++;
        }
        return metadata.substring(valueStart,valueEnd).trim();
    }

    private int rerankCandidateTopK() {
        if(ragProperties.getRerank() == null || ragProperties.getRerank().getCandidateTopK() == null
                || ragProperties.getRerank().getCandidateTopK() <= 0) {
            return DEFAULT_TOP_K;
        }
        return ragProperties.getRerank().getCandidateTopK();
    }

    private List<FileRagChunk> limitChunks(List<FileRagChunk> chunks, int limit) {
        if(chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(chunks.subList(0,Math.min(limit,chunks.size())));
    }

    /**
     * 构建 buildCitations 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param chunks 文件分片列表
     * @return 列表结果
     */
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
            citation.setSpaceId(spaceId);
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

    /**
     * 执行 splitText 函数的业务处理。
     *
     * @param text 文本内容
     * @param chunkSize 方法入参
     * @param chunkOverlap 方法入参
     * @return 列表结果
     */
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

    /**
     * 启动时清理上次进程遗留的超时索引任务。
     */
    @PostConstruct
    public void expireStaleIndexTasksOnStartup() {
        expireStaleIndexTasks(null);
    }

    /**
     * 将超过时限仍占用索引资源的任务标记为失败。
     *
     * @param spaceId 空间 ID，为 null 时处理所有空间
     */
    private void expireStaleIndexTasks(Long spaceId) {
        int timeoutMinutes = indexTimeoutMinutes();
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(timeoutMinutes);
        String errorMessage = "RAG indexing task timeout after " + timeoutMinutes + " minutes";
        for(SpaceRagTask task : spaceRagTaskMapper.listStaleIndexTasks(spaceId,cutoff)) {
            LocalDateTime now = LocalDateTime.now();
            int updated = spaceRagTaskMapper.failActiveTask(task.getId(),errorMessage,now,now);
            if(updated == 0) {
                continue;
            }
            if(task.getDocumentId() != null) {
                spaceRagDocumentMapper.updateIndexResult(task.getDocumentId(),SpaceConstant.RAG_INDEX_FAILED,0,errorMessage,now);
                continue;
            }
            if(SpaceConstant.RAG_TASK_REBUILD_SPACE.equals(task.getTaskType())) {
                spaceRagDocumentMapper.failStaleIndexingDocuments(task.getSpaceId(),cutoff,errorMessage,now);
            }
        }
    }

    /**
     * 查询 RAG 索引并发数。
     *
     * @return 并发数
     */
    private int indexConcurrency() {
        Integer concurrency = ragProperties.getIndex() == null ? null : ragProperties.getIndex().getConcurrency();
        return Math.max(1,concurrency == null ? 5 : concurrency);
    }

    /**
     * 查询 RAG 索引任务超时时间。
     *
     * @return 分钟数
     */
    private int indexTimeoutMinutes() {
        Integer minutes = ragProperties.getIndex() == null ? null : ragProperties.getIndex().getTaskTimeoutMinutes();
        return Math.max(1,minutes == null ? 10 : minutes);
    }

    /**
     * 创建 createTask 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param spaceFileId 空间文件 ID
     * @param documentId 文档 ID
     * @param taskType 任务类型
     * @param userId 用户 ID
     * @return 处理结果
     */
    private SpaceRagTask createTask(Long spaceId, Long spaceFileId, Long documentId, String taskType, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        SpaceRagTask task = new SpaceRagTask();
        task.setSpaceId(spaceId);
        task.setSpaceFileId(spaceFileId);
        task.setDocumentId(documentId);
        task.setTaskType(taskType);
        task.setTaskStatus(SpaceConstant.RAG_TASK_PENDING);
        task.setTotalCount(0);
        task.setSuccessCount(0);
        task.setFailedCount(0);
        task.setCreatedBy(userId);
        task.setCreatetime(now);
        task.setUpdatetime(now);
        spaceRagTaskMapper.insert(task);
        return task;
    }

    /**
     * 执行 finishTask 函数的业务处理。
     *
     * @param task 任务对象
     * @param status 状态
     * @param errorMessage 错误信息
     * @param startedTime 开始时间
     */
    private void finishTask(SpaceRagTask task, String status, String errorMessage, LocalDateTime startedTime) {
        spaceRagTaskMapper.updateResult(task.getId(),status,errorMessage,startedTime,LocalDateTime.now(),LocalDateTime.now());
    }

    /**
     * 执行 finishTask 函数的业务处理。
     *
     * @param taskId 任务 ID
     * @param status 状态
     * @param errorMessage 错误信息
     * @param startedTime 开始时间
     */
    private void finishTask(Long taskId, String status, String errorMessage, LocalDateTime startedTime) {
        spaceRagTaskMapper.updateResult(taskId,status,errorMessage,startedTime,LocalDateTime.now(),LocalDateTime.now());
    }

    /**
     * 标记 markTaskRunning 相关逻辑。
     *
     * @param taskId 任务 ID
     * @param startedTime 开始时间
     */
    private void markTaskRunning(Long taskId, LocalDateTime startedTime) {
        spaceRagTaskMapper.updateResult(taskId,SpaceConstant.RAG_TASK_RUNNING,null,startedTime,null,LocalDateTime.now());
    }

    /**
     * 更新 updateTaskProgress 相关逻辑。
     *
     * @param taskId 任务 ID
     * @param totalCount 方法入参
     * @param successCount 方法入参
     * @param failedCount 方法入参
     */
    private void updateTaskProgress(Long taskId, int totalCount, int successCount, int failedCount) {
        spaceRagTaskMapper.updateProgress(taskId,totalCount,successCount,failedCount,LocalDateTime.now());
    }

    /**
     * 派发 dispatchAfterCommit 相关逻辑。
     *
     * @param runnable 待执行任务
     */
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

    /**
     * 保存 saveQueryLog 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param userId 用户 ID
     * @param question 问题内容
     * @param answer 方法入参
     * @param hitChunkIds 方法入参
     * @param modelName 方法入参
     * @param success 方法入参
     * @param errorMessage 错误信息
     */
    private void saveQueryLog(Long spaceId, Long userId, String question, String answer, String hitChunkIds, String modelName, Integer topK, BigDecimal temperature, boolean success, String errorMessage) {
        SpaceRagQueryLog log = new SpaceRagQueryLog();
        log.setSpaceId(spaceId);
        log.setUserId(userId);
        log.setQuestion(question);
        log.setAnswer(answer);
        log.setHitChunkIds(hitChunkIds);
        log.setModelName(modelName);
        log.setTopK(topK);
        log.setTemperature(temperature);
        log.setPromptTokens(question == null ? 0 : question.length());
        log.setCompletionTokens(answer == null ? 0 : answer.length());
        log.setTotalTokens(log.getPromptTokens() + log.getCompletionTokens());
        log.setSuccess(success ? StatusConstant.ENABLE : StatusConstant.DISABLE);
        log.setErrorMessage(errorMessage);
        log.setCreatetime(LocalDateTime.now());
        spaceRagQueryLogMapper.insert(log);
    }

    /**
     * 转换 toConfigVO 相关逻辑。
     *
     * @param config 配置对象
     * @return 处理结果
     */
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
        vo.setTemperature(safeTemperature(config.getTemperature()));
        vo.setScoreThreshold(config.getScoreThreshold());
        vo.setEnabled(config.getEnabled());
        vo.setStatus(config.getStatus());
        vo.setCreatetime(config.getCreatetime());
        vo.setUpdatetime(config.getUpdatetime());
        return vo;
    }

    /**
     * 转换 toDocumentVO 相关逻辑。
     *
     * @param document 文档对象
     * @return 处理结果
     */
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

    /**
     * 转换 toTaskVO 相关逻辑。
     *
     * @param task 任务对象
     * @return 处理结果
     */
    private SpaceRagTaskVO toTaskVO(SpaceRagTask task) {
        SpaceRagTaskVO vo = new SpaceRagTaskVO();
        vo.setId(task.getId());
        vo.setSpaceId(task.getSpaceId());
        vo.setSpaceFileId(task.getSpaceFileId());
        vo.setDocumentId(task.getDocumentId());
        vo.setTaskType(task.getTaskType());
        vo.setTaskStatus(task.getTaskStatus());
        vo.setTotalCount(task.getTotalCount());
        vo.setSuccessCount(task.getSuccessCount());
        vo.setFailedCount(task.getFailedCount());
        vo.setErrorMessage(task.getErrorMessage());
        vo.setCreatedBy(task.getCreatedBy());
        vo.setStartedTime(task.getStartedTime());
        vo.setFinishedTime(task.getFinishedTime());
        vo.setCreatetime(task.getCreatetime());
        vo.setUpdatetime(task.getUpdatetime());
        return vo;
    }

    /**
     * 执行 fillDocumentLinks 函数的业务处理。
     *
     * @param spaceId 空间 ID
     * @param vo 方法入参
     */
    private void fillDocumentLinks(Long spaceId, SpaceDocumentSearchVO vo) {
        String base = "/api/space/" + spaceId + "/files/" + vo.getSpaceFileId();
        vo.setPreviewUrl(base + "/preview");
        vo.setStreamUrl(base + "/preview/stream");
        vo.setDownloadUrl(base + "/download");
    }

    /**
     * 执行 safeChunkSize 函数的业务处理。
     *
     * @param chunkSize 方法入参
     * @return 影响行数
     */
    private int safeChunkSize(Integer chunkSize) {
        return chunkSize == null || chunkSize <= 0 ? DEFAULT_CHUNK_SIZE : chunkSize;
    }

    /**
     * 执行 safeChunkOverlap 函数的业务处理。
     *
     * @param chunkOverlap 方法入参
     * @param chunkSize 方法入参
     * @return 影响行数
     */
    private int safeChunkOverlap(Integer chunkOverlap, Integer chunkSize) {
        int realChunkSize = safeChunkSize(chunkSize);
        if(chunkOverlap == null || chunkOverlap < 0) {
            return DEFAULT_CHUNK_OVERLAP;
        }
        return Math.min(chunkOverlap,realChunkSize - 1);
    }

    private int resolveQueryTopK(SpaceRagConfig config, String retrievalMode) {
        int configured = safeConfiguredTopK(config == null ? null : config.getTopK());
        String mode = retrievalMode == null ? "balanced" : retrievalMode.trim().toLowerCase();
        if("precise".equals(mode)) {
            return Math.max(1,Math.min(configured,Math.max(1,(int) Math.ceil(configured * 0.4))));
        }
        if("broad".equals(mode)) {
            return configured;
        }
        return Math.max(1,Math.min(configured,Math.max(1,(int) Math.ceil(configured * 0.7))));
    }

    private Integer safeConfiguredTopK(Integer topK) {
        if(topK == null || topK <= 0) {
            return DEFAULT_TOP_K;
        }
        return Math.min(topK,MAX_TOP_K);
    }

    private BigDecimal safeTemperature(BigDecimal temperature) {
        BigDecimal value = temperature == null ? BigDecimal.valueOf(ragProperties.getChat().getTemperature() == null ? 0.2 : ragProperties.getChat().getTemperature()) : temperature;
        if(value.compareTo(BigDecimal.ZERO) < 0) {
            value = BigDecimal.ZERO;
        }
        if(value.compareTo(BigDecimal.ONE) > 0) {
            value = BigDecimal.ONE;
        }
        return value.setScale(2,RoundingMode.HALF_UP);
    }

    private void saveConfigChangeLog(Long spaceId, Long operatorId, SpaceRagConfig before, SpaceRagConfig after) {
        String changedFields = changedFields(before,after);
        if(changedFields.isBlank()) {
            return;
        }
        SpaceRagConfigLog changeLog = new SpaceRagConfigLog();
        changeLog.setSpaceId(spaceId);
        changeLog.setOperatorId(operatorId);
        changeLog.setChangedFields(changedFields);
        changeLog.setBeforeJson(configSnapshot(before));
        changeLog.setAfterJson(configSnapshot(after));
        changeLog.setCreatetime(LocalDateTime.now());
        spaceRagConfigLogMapper.insert(changeLog);
        log.info("RAG config updated: spaceId={}, operatorId={}, changedFields={}, before={}, after={}",
                spaceId,operatorId,changedFields,changeLog.getBeforeJson(),changeLog.getAfterJson());
    }

    private String changedFields(SpaceRagConfig before, SpaceRagConfig after) {
        List<String> fields = new ArrayList<>();
        addChanged(fields,"embeddingModel",before.getEmbeddingModel(),after.getEmbeddingModel());
        addChanged(fields,"chatModel",before.getChatModel(),after.getChatModel());
        addChanged(fields,"chunkSize",before.getChunkSize(),after.getChunkSize());
        addChanged(fields,"chunkOverlap",before.getChunkOverlap(),after.getChunkOverlap());
        addChanged(fields,"topK",before.getTopK(),after.getTopK());
        addChanged(fields,"temperature",safeTemperature(before.getTemperature()),safeTemperature(after.getTemperature()));
        addChanged(fields,"scoreThreshold",before.getScoreThreshold(),after.getScoreThreshold());
        addChanged(fields,"enabled",before.getEnabled(),after.getEnabled());
        return String.join(",",fields);
    }

    private void addChanged(List<String> fields, String field, Object before, Object after) {
        if(before == null ? after != null : !before.equals(after)) {
            fields.add(field);
        }
    }

    private String configSnapshot(SpaceRagConfig config) {
        if(config == null) {
            return "{}";
        }
        return "{\"embeddingModel\":\"" + escapeJson(config.getEmbeddingModel()) + "\","
                + "\"chatModel\":\"" + escapeJson(config.getChatModel()) + "\","
                + "\"chunkSize\":" + config.getChunkSize() + ","
                + "\"chunkOverlap\":" + config.getChunkOverlap() + ","
                + "\"topK\":" + config.getTopK() + ","
                + "\"temperature\":" + safeTemperature(config.getTemperature()) + ","
                + "\"scoreThreshold\":" + config.getScoreThreshold() + ","
                + "\"enabled\":" + config.getEnabled() + "}";
    }

    /**
     * 执行 blankToCurrent 函数的业务处理。
     *
     * @param value 方法入参
     * @param current 方法入参
     * @return 处理结果
     */
    private String blankToCurrent(String value, String current) {
        return value == null || value.isBlank() ? current : value;
    }

    /**
     * 执行 truncate 函数的业务处理。
     *
     * @param value 方法入参
     * @param maxLength 方法入参
     * @return 处理结果
     */
    private String truncate(String value, int maxLength) {
        if(value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0,maxLength);
    }

    /**
     * 执行 sha256 函数的业务处理。
     *
     * @param value 方法入参
     * @return 处理结果
     */
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

    /**
     * 执行 escapeJson 函数的业务处理。
     *
     * @param value 方法入参
     * @return 处理结果
     */
    private String escapeJson(String value) {
        if(value == null) {
            return "";
        }
        return value.replace("\\","\\\\").replace("\"","\\\"");
    }
}
