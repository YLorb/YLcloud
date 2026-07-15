package com.ylcloud.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.DTO.SpaceKnowledgeProfileUpdateDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.VO.SpaceKnowledgeDashboardVO;
import com.ylcloud.VO.SpaceKnowledgeDocumentProfileVO;
import com.ylcloud.VO.SpaceKnowledgeDocumentItemVO;
import com.ylcloud.VO.SpaceKnowledgeFacetVO;
import com.ylcloud.VO.SpaceKnowledgePipelineTaskVO;
import com.ylcloud.VO.SpaceKnowledgeProfileDiffVO;
import com.ylcloud.VO.SpaceKnowledgeProfileVersionVO;
import com.ylcloud.config.RagProperties;
import com.ylcloud.constant.SpaceConstant;
import com.ylcloud.constant.StatusConstant;
import com.ylcloud.entity.FileRagChunk;
import com.ylcloud.entity.SpaceKnowledgeDocumentProfile;
import com.ylcloud.entity.SpaceKnowledgePipelineTask;
import com.ylcloud.entity.SpaceKnowledgeQuestion;
import com.ylcloud.entity.SpaceRagDocument;
import com.ylcloud.entity.SpaceRagConfig;
import com.ylcloud.mapper.FileRagChunkMapper;
import com.ylcloud.mapper.SpaceKnowledgeDocumentProfileMapper;
import com.ylcloud.mapper.SpaceKnowledgePipelineTaskMapper;
import com.ylcloud.mapper.SpaceKnowledgeQuestionMapper;
import com.ylcloud.mapper.SpaceRagDocumentMapper;
import com.ylcloud.mapper.SpaceRagMapper;
import com.ylcloud.service.knowledge.event.PipelineEventService;
import com.ylcloud.service.knowledge.pipeline.KnowledgePipelineIncrementalDecision;
import com.ylcloud.service.knowledge.pipeline.KnowledgePipelineIncrementalService;
import com.ylcloud.service.knowledge.pipeline.KnowledgePipelineContext;
import com.ylcloud.service.knowledge.pipeline.KnowledgeSourceSnapshot;
import com.ylcloud.service.knowledge.pipeline.KnowledgeSourceSnapshotService;
import com.ylcloud.service.knowledge.profile.KnowledgeProfileDraft;
import com.ylcloud.service.knowledge.profile.KnowledgeProfileNormalizer;
import com.ylcloud.service.knowledge.profile.KnowledgeProfileParseResult;
import com.ylcloud.service.knowledge.profile.KnowledgeProfileParser;
import com.ylcloud.service.knowledge.profile.KnowledgeProfileRepairService;
import com.ylcloud.service.knowledge.profile.KnowledgeProfileValidationResult;
import com.ylcloud.service.knowledge.profile.KnowledgeProfileValidator;
import com.ylcloud.service.knowledge.quality.KnowledgeProfileQualityResult;
import com.ylcloud.service.knowledge.quality.KnowledgeProfileQualityService;
import com.ylcloud.service.knowledge.quality.QualityIssue;
import com.ylcloud.service.rag.RagGenerateRequest;
import com.ylcloud.service.rag.RagGenerateResponse;
import com.ylcloud.service.rag.RagModelClient;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class KnowledgePipelineService {
    private static final int MAX_CONTEXT_CHARS = 8000;
    private static final int MAX_QUESTIONS = 8;

    private final SpacePermissionService spacePermissionService;
    private final SpaceRagDocumentMapper spaceRagDocumentMapper;
    private final FileRagChunkMapper fileRagChunkMapper;
    private final SpaceKnowledgeDocumentProfileMapper profileMapper;
    private final SpaceKnowledgeQuestionMapper questionMapper;
    private final SpaceKnowledgePipelineTaskMapper taskMapper;
    private final KnowledgeProfileWriteService knowledgeProfileWriteService;
    private final KnowledgeProfileAssetService knowledgeProfileAssetService;
    private final PipelineEventService pipelineEventService;
    private final KnowledgePipelineIncrementalService knowledgePipelineIncrementalService;
    private final KnowledgeSourceSnapshotService knowledgeSourceSnapshotService;
    private final KnowledgeProfileParser knowledgeProfileParser;
    private final KnowledgeProfileNormalizer knowledgeProfileNormalizer;
    private final KnowledgeProfileValidator knowledgeProfileValidator;
    private final KnowledgeProfileQualityService knowledgeProfileQualityService;
    private final KnowledgeProfileRepairService knowledgeProfileRepairService;
    private final RagModelClient ragModelClient;
    private final RagProperties ragProperties;
    private final SpaceRagMapper spaceRagMapper;
    private final ObjectMapper objectMapper;

    public KnowledgePipelineService(SpacePermissionService spacePermissionService,
                                    SpaceRagDocumentMapper spaceRagDocumentMapper,
                                    FileRagChunkMapper fileRagChunkMapper,
                                    SpaceKnowledgeDocumentProfileMapper profileMapper,
                                    SpaceKnowledgeQuestionMapper questionMapper,
                                    SpaceKnowledgePipelineTaskMapper taskMapper,
                                    KnowledgeProfileWriteService knowledgeProfileWriteService,
                                    KnowledgeProfileAssetService knowledgeProfileAssetService,
                                    PipelineEventService pipelineEventService,
                                    KnowledgePipelineIncrementalService knowledgePipelineIncrementalService,
                                    KnowledgeSourceSnapshotService knowledgeSourceSnapshotService,
                                    KnowledgeProfileParser knowledgeProfileParser,
                                    KnowledgeProfileNormalizer knowledgeProfileNormalizer,
                                    KnowledgeProfileValidator knowledgeProfileValidator,
                                    KnowledgeProfileQualityService knowledgeProfileQualityService,
                                    KnowledgeProfileRepairService knowledgeProfileRepairService,
                                    RagModelClient ragModelClient,
                                    RagProperties ragProperties,
                                    SpaceRagMapper spaceRagMapper,
                                    ObjectMapper objectMapper) {
        this.spacePermissionService = spacePermissionService;
        this.spaceRagDocumentMapper = spaceRagDocumentMapper;
        this.fileRagChunkMapper = fileRagChunkMapper;
        this.profileMapper = profileMapper;
        this.questionMapper = questionMapper;
        this.taskMapper = taskMapper;
        this.knowledgeProfileWriteService = knowledgeProfileWriteService;
        this.knowledgeProfileAssetService = knowledgeProfileAssetService;
        this.pipelineEventService = pipelineEventService;
        this.knowledgePipelineIncrementalService = knowledgePipelineIncrementalService;
        this.knowledgeSourceSnapshotService = knowledgeSourceSnapshotService;
        this.knowledgeProfileParser = knowledgeProfileParser;
        this.knowledgeProfileNormalizer = knowledgeProfileNormalizer;
        this.knowledgeProfileValidator = knowledgeProfileValidator;
        this.knowledgeProfileQualityService = knowledgeProfileQualityService;
        this.knowledgeProfileRepairService = knowledgeProfileRepairService;
        this.ragModelClient = ragModelClient;
        this.ragProperties = ragProperties;
        this.spaceRagMapper = spaceRagMapper;
        this.objectMapper = objectMapper;
    }

    public SpaceKnowledgePipelineTaskVO submitDocument(Long spaceId, Long documentId, Long userId) {
        spacePermissionService.requireAdmin(spaceId,userId);
        requireKnowledgeProfileEnabled(spaceId);
        return toTaskVO(createDocumentProfileTask(spaceId,documentId,userId,SpaceConstant.KNOWLEDGE_STAGE_WAITING_RAG,true));
    }

    public SpaceKnowledgePipelineTaskVO submitDocumentProfileTask(Long spaceId, Long documentId, Long userId) {
        requireKnowledgeProfileEnabled(spaceId);
        return toTaskVO(createDocumentProfileTask(spaceId,documentId,userId,SpaceConstant.KNOWLEDGE_STAGE_WAITING_RAG,false));
    }

    public SpaceKnowledgePipelineTaskVO submitDocumentProfileTaskIfAbsent(Long spaceId, Long documentId, Long userId) {
        requireKnowledgeProfileEnabled(spaceId);
        SpaceRagDocument document = requireDocument(spaceId,documentId);
        SpaceKnowledgePipelineTask activeTask = taskMapper.getActiveDocumentTask(spaceId,document.getId());
        if(activeTask != null) {
            return null;
        }
        return toTaskVO(createDocumentProfileTask(spaceId,documentId,userId,SpaceConstant.KNOWLEDGE_STAGE_WAITING_RAG,false));
    }

    public SpaceKnowledgePipelineTaskVO recordDocumentProfileSkipped(Long spaceId, Long documentId, Long userId, String reason) {
        SpaceRagDocument document = requireDocument(spaceId,documentId);
        SpaceKnowledgePipelineTask active = taskMapper.getActiveDocumentTask(spaceId,document.getId());
        if(active != null) {
            return toTaskVO(active);
        }
        SpaceKnowledgePipelineTask task = createTask(
                spaceId,document.getId(),SpaceConstant.KNOWLEDGE_TASK_PROFILE_DOCUMENT,userId,false);
        LocalDateTime finished = LocalDateTime.now();
        updateFlow(task.getId(),SpaceConstant.KNOWLEDGE_TASK_SKIPPED,SpaceConstant.KNOWLEDGE_STAGE_SKIPPED,
                100,1,0,0,null,null,finished);
        taskMapper.updateIncremental(task.getId(),SpaceConstant.KNOWLEDGE_STAGE_SKIPPED,reason,
                null,null,finished);
        return toTaskVO(taskMapper.getById(task.getId()));
    }

    public SpaceKnowledgePipelineTaskVO recordSpaceProfileSkipped(Long spaceId, Long userId, String reason) {
        SpaceKnowledgePipelineTask active = taskMapper.getActiveSpaceTask(spaceId);
        if(active != null) {
            return toTaskVO(active);
        }
        SpaceKnowledgePipelineTask task = createTask(
                spaceId,null,SpaceConstant.KNOWLEDGE_TASK_PROFILE_SPACE,userId,false);
        markTaskSkipped(task,reason);
        return toTaskVO(taskMapper.getById(task.getId()));
    }

    public void executeTask(Long taskId) {
        SpaceKnowledgePipelineTask task = taskMapper.getById(taskId);
        if(task == null) {
            return;
        }
        LocalDateTime started = LocalDateTime.now();
        if(taskMapper.markRunningIfPending(taskId,started) != 1) {
            return;
        }
        if(!isKnowledgeProfileEnabled(task.getSpaceId())) {
            markRunningTaskSkipped(task,SpaceConstant.KNOWLEDGE_TERMINAL_PROFILE_DISABLED);
            return;
        }
        updateRunningFlow(task.getId(),SpaceConstant.KNOWLEDGE_TASK_RUNNING,SpaceConstant.KNOWLEDGE_PIPELINE_LOAD_CHUNKS,10,1,0,0,null,started,null);
        SpaceRagDocument document = null;
        try {
            document = requireDocument(task.getSpaceId(),task.getDocumentId());
            KnowledgePipelineContext context = createPipelineContext(task,document);
            SpaceKnowledgeDocumentProfileVO profile = runDocumentPipeline(context);
            if(updateRunningFlow(task.getId(),SpaceConstant.KNOWLEDGE_TASK_SUCCESS,SpaceConstant.KNOWLEDGE_STAGE_SUCCESS,100,1,1,0,null,started,LocalDateTime.now()) != 1) {
                return;
            }
            pipelineEventService.taskFinished(context,SpaceConstant.KNOWLEDGE_EVENT_STATUS_SUCCEEDED,
                    "Document pipeline finished with profile status " + profile.getProfileStatus());
        } catch (Exception ex) {
            String message = safeError(ex);
            if(document != null) {
                saveFailedProfile(document,message);
            }
            updateRunningFlow(task.getId(),SpaceConstant.KNOWLEDGE_TASK_FAILED,SpaceConstant.KNOWLEDGE_STAGE_FAILED,100,1,0,1,message,started,LocalDateTime.now());
        }
    }

    public SpaceKnowledgePipelineTaskVO submitSpace(Long spaceId, Long userId) {
        spacePermissionService.requireAdmin(spaceId,userId);
        requireKnowledgeProfileEnabled(spaceId);
        if(taskMapper.getActiveSpaceTask(spaceId) != null) {
            throw new BaseException("当前知识库已有正在执行的知识画像批次");
        }
        return createSpaceProfileTask(spaceId,userId);
    }

    public SpaceKnowledgePipelineTaskVO submitSpaceProfileTaskIfAbsent(Long spaceId, Long userId) {
        requireKnowledgeProfileEnabled(spaceId);
        if(taskMapper.getActiveSpaceTask(spaceId) != null) {
            return null;
        }
        return createSpaceProfileTask(spaceId,userId);
    }

    private SpaceKnowledgePipelineTaskVO createSpaceProfileTask(Long spaceId, Long userId) {
        SpaceKnowledgePipelineTask task = createTask(spaceId,null,SpaceConstant.KNOWLEDGE_TASK_PROFILE_SPACE,userId,true);
        pipelineEventService.taskCreated(task.getId(),spaceId,null,"knowledge-" + task.getId());
        return toTaskVO(task);
    }

    public void executeSpaceTask(Long taskId) {
        SpaceKnowledgePipelineTask task = taskMapper.getById(taskId);
        if(task == null) {
            return;
        }
        LocalDateTime started = LocalDateTime.now();
        if(taskMapper.markRunningIfPending(taskId,started) != 1) {
            return;
        }
        if(!isKnowledgeProfileEnabled(task.getSpaceId())) {
            markRunningTaskSkipped(task,SpaceConstant.KNOWLEDGE_TERMINAL_PROFILE_DISABLED);
            return;
        }
        List<SpaceRagDocument> documents = spaceRagDocumentMapper.listBySpaceId(task.getSpaceId()).stream()
                .filter(document -> SpaceConstant.RAG_INDEX_READY.equals(document.getIndexStatus()))
                .toList();
        if(documents.isEmpty()) {
            markRunningTaskSkipped(task,SpaceConstant.KNOWLEDGE_TERMINAL_NO_ELIGIBLE_DOCUMENTS);
            return;
        }
        updateRunningFlow(taskId,SpaceConstant.KNOWLEDGE_TASK_RUNNING,SpaceConstant.KNOWLEDGE_STAGE_PROFILING,10,documents.size(),0,0,null,started,null);
        int success = 0;
        int failed = 0;
        int needsReview = 0;
        for(SpaceRagDocument document : documents) {
            try {
                KnowledgePipelineContext context = createPipelineContext(task,document);
                SpaceKnowledgeDocumentProfileVO profile = runDocumentPipeline(context);
                if(SpaceConstant.KNOWLEDGE_PROFILE_NEEDS_REVIEW.equals(profile.getProfileStatus())) {
                    needsReview++;
                }
                success++;
            } catch (Exception ex) {
                failed++;
                saveFailedProfile(document,safeError(ex));
            } finally {
                int done = success + failed;
                int progress = documents.isEmpty() ? 100 : Math.min(95,10 + (int) Math.floor(done * 80.0 / documents.size()));
                updateRunningFlow(taskId,SpaceConstant.KNOWLEDGE_TASK_RUNNING,SpaceConstant.KNOWLEDGE_STAGE_PROFILING,progress,documents.size(),success,failed,null,started,null);
            }
        }
        String status = spaceTaskStatus(success,failed);
        String error = failed == 0 ? null : failed + " document(s) failed";
        String terminalStage = SpaceConstant.KNOWLEDGE_TASK_FAILED.equals(status)
                ? SpaceConstant.KNOWLEDGE_STAGE_FAILED : SpaceConstant.KNOWLEDGE_STAGE_SUCCESS;
        updateRunningFlow(taskId,status,terminalStage,100,documents.size(),success,failed,error,started,LocalDateTime.now());
    }

    public SpaceKnowledgeDocumentProfileVO getProfile(Long spaceId, Long documentId, Long userId) {
        spacePermissionService.requireMember(spaceId,userId);
        SpaceKnowledgeDocumentProfile profile = profileMapper.getByDocumentId(spaceId,documentId);
        if(profile == null) {
            return null;
        }
        return toProfileVO(profile);
    }

    public List<SpaceKnowledgeDocumentProfileVO> listProfiles(Long spaceId, Long userId) {
        spacePermissionService.requireMember(spaceId,userId);
        List<SpaceKnowledgeDocumentProfileVO> result = new ArrayList<>();
        for(SpaceKnowledgeDocumentProfile profile : profileMapper.listBySpaceId(spaceId)) {
            result.add(toProfileVO(profile));
        }
        return result;
    }

    public List<SpaceKnowledgeDocumentItemVO> listDocuments(Long spaceId, String category, String tag, String profileStatus, Long userId) {
        spacePermissionService.requireMember(spaceId,userId);
        Map<Long, SpaceKnowledgeDocumentProfile> profiles = new LinkedHashMap<>();
        for(SpaceKnowledgeDocumentProfile profile : profileMapper.listFiltered(spaceId,category,tag,profileStatus)) {
            profiles.put(profile.getDocumentId(),profile);
        }
        List<SpaceKnowledgeDocumentItemVO> result = new ArrayList<>();
        for(SpaceRagDocument document : spaceRagDocumentMapper.listBySpaceId(spaceId)) {
            SpaceKnowledgeDocumentProfile profile = profiles.get(document.getId());
            if((category != null && !category.isBlank() || tag != null && !tag.isBlank() || profileStatus != null && !profileStatus.isBlank())
                    && profile == null) {
                continue;
            }
            result.add(toDocumentItemVO(document,profile));
        }
        return result;
    }

    public SpaceKnowledgeDashboardVO dashboard(Long spaceId, Long userId) {
        spacePermissionService.requireMember(spaceId,userId);
        List<SpaceRagDocument> documents = spaceRagDocumentMapper.listBySpaceId(spaceId);
        List<SpaceKnowledgeDocumentProfile> profiles = profileMapper.listBySpaceId(spaceId);
        List<SpaceKnowledgePipelineTask> tasks = taskMapper.listBySpaceId(spaceId,200);
        SpaceKnowledgeDashboardVO vo = new SpaceKnowledgeDashboardVO();
        vo.setSpaceId(spaceId);
        vo.setDocumentCount(documents.size());
        vo.setIndexedCount((int) documents.stream().filter(document -> SpaceConstant.RAG_INDEX_SUCCESS.equals(document.getIndexStatus())).count());
        vo.setProfiledCount((int) profiles.stream().filter(profile -> SpaceConstant.KNOWLEDGE_PROFILE_VALID.equals(profile.getProfileStatus())
                || SpaceConstant.KNOWLEDGE_PROFILE_SUCCESS.equals(profile.getProfileStatus())
                || SpaceConstant.KNOWLEDGE_PROFILE_NEEDS_REVIEW.equals(profile.getProfileStatus())).count());
        vo.setFailedProfileCount((int) profiles.stream().filter(profile -> SpaceConstant.KNOWLEDGE_PROFILE_INVALID.equals(profile.getProfileStatus())
                || SpaceConstant.KNOWLEDGE_PROFILE_FAILED.equals(profile.getProfileStatus())).count());
        vo.setNeedsReviewCount((int) profiles.stream().filter(profile -> SpaceConstant.KNOWLEDGE_PROFILE_NEEDS_REVIEW.equals(profile.getProfileStatus())).count());
        vo.setAverageQualityScore(averageQuality(profiles));
        List<SpaceKnowledgeFacetVO> categories = categoryFacets(profiles);
        List<SpaceKnowledgeFacetVO> tags = tagFacets(profiles);
        vo.setCategories(categories);
        vo.setTags(tags);
        vo.setCategoryCount(categories.size());
        vo.setTagCount(tags.size());
        vo.setPendingTaskCount((int) tasks.stream().filter(task -> SpaceConstant.KNOWLEDGE_TASK_PENDING.equals(task.getTaskStatus())).count());
        vo.setRunningTaskCount((int) tasks.stream().filter(task -> SpaceConstant.KNOWLEDGE_TASK_RUNNING.equals(task.getTaskStatus())).count());
        vo.setFailedTaskCount((int) tasks.stream().filter(task -> SpaceConstant.KNOWLEDGE_TASK_FAILED.equals(task.getTaskStatus())
                || SpaceConstant.KNOWLEDGE_TASK_PARTIAL_SUCCESS.equals(task.getTaskStatus())).count());
        vo.setRecentFailedTasks(tasks.stream()
                .filter(task -> SpaceConstant.KNOWLEDGE_TASK_FAILED.equals(task.getTaskStatus())
                        || SpaceConstant.KNOWLEDGE_TASK_PARTIAL_SUCCESS.equals(task.getTaskStatus()))
                .limit(5)
                .map(this::toTaskVO)
                .toList());
        return vo;
    }

    public List<SpaceKnowledgeFacetVO> categories(Long spaceId, Long userId) {
        spacePermissionService.requireMember(spaceId,userId);
        return categoryFacets(profileMapper.listBySpaceId(spaceId));
    }

    public List<SpaceKnowledgeFacetVO> tags(Long spaceId, Long userId) {
        spacePermissionService.requireMember(spaceId,userId);
        return tagFacets(profileMapper.listBySpaceId(spaceId));
    }

    public List<SpaceKnowledgePipelineTaskVO> listTasks(Long spaceId, Long userId) {
        spacePermissionService.requireAdmin(spaceId,userId);
        List<SpaceKnowledgePipelineTaskVO> result = new ArrayList<>();
        for(SpaceKnowledgePipelineTask task : taskMapper.listBySpaceId(spaceId,50)) {
            result.add(toTaskVO(task));
        }
        return result;
    }

    public SpaceKnowledgePipelineTaskVO requireTask(Long spaceId, Long taskId, Long userId) {
        spacePermissionService.requireAdmin(spaceId,userId);
        SpaceKnowledgePipelineTask task = taskMapper.getById(taskId);
        if(task == null || !spaceId.equals(task.getSpaceId())) {
            throw new BaseException("knowledge pipeline task not found");
        }
        return toTaskVO(task);
    }

    public SpaceKnowledgeDocumentProfileVO updateProfile(Long spaceId, Long documentId, SpaceKnowledgeProfileUpdateDTO payload, Long userId) {
        spacePermissionService.requireAdmin(spaceId,userId);
        SpaceKnowledgeDocumentProfile current = profileMapper.getByDocumentId(spaceId,documentId);
        if(current == null) {
            throw new BaseException("knowledge profile not found");
        }
        String title = blankToDefault(payload.getTitle(),current.getTitle());
        String summary = payload.getSummary() == null ? current.getSummary() : payload.getSummary();
        String category = blankToDefault(payload.getCategory(),current.getCategory());
        String profileStatus = blankToDefault(payload.getProfileStatus(),SpaceConstant.KNOWLEDGE_PROFILE_VALID);
        String keywordsJson = payload.getKeywords() == null ? current.getKeywordsJson() : toJson(payload.getKeywords());
        String tagsJson = payload.getTags() == null ? current.getTagsJson() : toJson(payload.getTags());
        SpaceKnowledgeDocumentProfile updated = knowledgeProfileWriteService.updateProfile(
                spaceId,documentId,title,summary,keywordsJson,tagsJson,category,profileStatus,payload.getQuestions(),userId);
        return toProfileVO(updated);
    }

    public SpaceKnowledgeDocumentProfileVO markReviewed(Long spaceId, Long documentId, Long userId) {
        SpaceKnowledgeProfileUpdateDTO payload = new SpaceKnowledgeProfileUpdateDTO();
        payload.setProfileStatus(SpaceConstant.KNOWLEDGE_PROFILE_VALID);
        SpaceKnowledgeDocumentProfileVO profile = updateProfile(spaceId,documentId,payload,userId);
        knowledgeProfileAssetService.audit(spaceId,userId,"PROFILE_APPROVE","KNOWLEDGE_PROFILE",profile.getId(),null,null);
        return profile;
    }

    public List<SpaceKnowledgeProfileVersionVO> listProfileVersions(Long spaceId, Long documentId, Long userId) {
        spacePermissionService.requireMember(spaceId,userId);
        return knowledgeProfileAssetService.listVersions(spaceId,documentId);
    }

    public SpaceKnowledgeProfileDiffVO diffProfileVersion(Long spaceId, Long documentId, Long versionId, Long compareToVersionId, Long userId) {
        spacePermissionService.requireMember(spaceId,userId);
        return knowledgeProfileAssetService.diff(spaceId,documentId,versionId,compareToVersionId);
    }

    public SpaceKnowledgeDocumentProfileVO restoreProfileVersion(Long spaceId, Long documentId, Long versionId, Long userId) {
        spacePermissionService.requireAdmin(spaceId,userId);
        return toProfileVO(knowledgeProfileAssetService.restoreVersion(spaceId,documentId,versionId,userId));
    }

    public SpaceKnowledgePipelineTaskVO retryTask(Long spaceId, Long taskId, Long userId) {
        spacePermissionService.requireAdmin(spaceId,userId);
        SpaceKnowledgePipelineTask failedTask = taskMapper.getById(taskId);
        if(failedTask == null || !spaceId.equals(failedTask.getSpaceId())) {
            throw new BaseException("knowledge pipeline task not found");
        }
        if(!SpaceConstant.KNOWLEDGE_TASK_FAILED.equals(failedTask.getTaskStatus())
                && !SpaceConstant.KNOWLEDGE_TASK_PARTIAL_SUCCESS.equals(failedTask.getTaskStatus())) {
            throw new BaseException("only failed knowledge pipeline tasks can be retried");
        }
        if(failedTask.getDocumentId() != null) {
            return submitDocumentProfileTask(spaceId,failedTask.getDocumentId(),userId);
        }
        return submitSpace(spaceId,userId);
    }

    public List<SpaceKnowledgePipelineTaskVO> retryFailedTasks(Long spaceId, Long userId) {
        spacePermissionService.requireAdmin(spaceId,userId);
        List<SpaceKnowledgePipelineTaskVO> result = new ArrayList<>();
        for(SpaceKnowledgePipelineTask task : taskMapper.listFailedBySpaceId(spaceId,50)) {
            result.add(retryTask(spaceId,task.getId(),userId));
        }
        return result;
    }

    private SpaceKnowledgeDocumentProfileVO runDocumentPipeline(KnowledgePipelineContext context) {
        GeneratedProfileContext profileContext = pipelineEventService.executeStage(context,SpaceConstant.KNOWLEDGE_PIPELINE_LOAD_CHUNKS,
                context.getDocument().getFileName(),() -> buildProfileContext(context.getDocument()));
        context.setChunks(profileContext.usedChunks());
        context.setContentContext(profileContext.context());
        SpaceKnowledgeDocumentProfile currentProfile = profileMapper.getByDocumentId(context.getSpaceId(),context.getDocumentId());
        KnowledgeSourceSnapshot sourceSnapshot = knowledgeSourceSnapshotService.create(
                profileContext.usedChunks(),profileContext.context().length(),knowledgePipelineIncrementalService.parserVersion());
        KnowledgePipelineIncrementalDecision incrementalDecision = pipelineEventService.executeStage(context,
                SpaceConstant.KNOWLEDGE_PIPELINE_CHECK_INCREMENTAL,
                "fileHash=" + blankToDefault(context.getDocument().getFileHash(),""),
                () -> knowledgePipelineIncrementalService.decide(
                        context.getDocument(),
                        currentProfile,
                        sourceSnapshot.sourceChunkCount(),
                        sourceSnapshot.sourceCharacterCount(),
                        sourceSnapshot.sourceChunkIds(),
                        sourceSnapshot.signature(),
                        context.isForceRebuild()));
        taskMapper.updateIncremental(context.getTaskId(),incrementalDecision.terminalStage(),incrementalDecision.terminalReason(),
                incrementalDecision.action(),incrementalDecision.detail(),LocalDateTime.now());
        if(incrementalDecision.skipsProfile()) {
            SpaceKnowledgeDocumentProfile existing = profileMapper.getByDocumentId(context.getSpaceId(),context.getDocumentId());
            return toProfileVO(existing);
        }
        if(incrementalDecision.syncsRetrievalSource()) {
            updateRunningFlow(context.getTaskId(),SpaceConstant.KNOWLEDGE_TASK_RUNNING,SpaceConstant.KNOWLEDGE_PIPELINE_SYNC_RETRIEVAL_SOURCE,95,1,1,0,null,null,null);
            SpaceKnowledgeDocumentProfile refreshed = pipelineEventService.executeStage(context,
                    SpaceConstant.KNOWLEDGE_PIPELINE_SYNC_RETRIEVAL_SOURCE,
                    incrementalDecision.detail(),
                    () -> knowledgeProfileWriteService.syncRetrievalSource(
                            context.getSpaceId(),
                            context.getDocumentId(),
                            sourceSnapshot,
                            currentProfile == null ? 0L : currentProfile.getSourceSnapshotRevision()));
            return toProfileVO(refreshed);
        }
        updateRunningFlow(context.getTaskId(),SpaceConstant.KNOWLEDGE_TASK_RUNNING,SpaceConstant.KNOWLEDGE_PIPELINE_GENERATE_PROFILE,25,1,0,0,null,null,null);

        String rawOutput = pipelineEventService.executeStage(context,SpaceConstant.KNOWLEDGE_PIPELINE_GENERATE_PROFILE,
                "contextChars=" + profileContext.context().length(),() -> generateProfileRaw(context.getDocument(),profileContext.context()));
        context.setRawLlmOutput(rawOutput);
        updateRunningFlow(context.getTaskId(),SpaceConstant.KNOWLEDGE_TASK_RUNNING,SpaceConstant.KNOWLEDGE_PIPELINE_PARSE_PROFILE,40,1,0,0,null,null,null);

        KnowledgeProfileParseResult parseResult = pipelineEventService.executeStage(context,SpaceConstant.KNOWLEDGE_PIPELINE_PARSE_PROFILE,
                "rawChars=" + rawOutput.length(),() -> knowledgeProfileParser.parse(rawOutput));
        KnowledgeProfileDraft parsed = parseResult.isParsed() ? parseResult.getProfile() : fallbackDraft(context.getDocument(),profileContext.context());
        parsed.setSchemaValid(parseResult.isParsed());
        context.setProfile(parsed);
        updateRunningFlow(context.getTaskId(),SpaceConstant.KNOWLEDGE_TASK_RUNNING,SpaceConstant.KNOWLEDGE_PIPELINE_NORMALIZE_PROFILE,50,1,0,0,null,null,null);

        KnowledgeProfileDraft normalized = pipelineEventService.executeStage(context,SpaceConstant.KNOWLEDGE_PIPELINE_NORMALIZE_PROFILE,
                "profile",() -> knowledgeProfileNormalizer.normalize(context.getProfile(),context.getDocument()));
        context.setProfile(normalized);

        KnowledgeProfileValidationResult validation = pipelineEventService.executeStage(context,SpaceConstant.KNOWLEDGE_PIPELINE_VALIDATE_PROFILE,
                "schemaValid=" + normalized.isSchemaValid(),() -> knowledgeProfileValidator.validate(normalized));
        context.setValidationResult(validation);
        updateRunningFlow(context.getTaskId(),SpaceConstant.KNOWLEDGE_TASK_RUNNING,SpaceConstant.KNOWLEDGE_PIPELINE_SCORE_PROFILE,65,1,0,0,null,null,null);

        KnowledgeProfileQualityResult before = pipelineEventService.executeStage(context,SpaceConstant.KNOWLEDGE_PIPELINE_SCORE_PROFILE,
                "beforeRepair",() -> knowledgeProfileQualityService.evaluate(context.getProfile(),context.getValidationResult(),
                        context.getChunks(),profileContext.totalContentChunkCount()));
        context.setScoreBeforeRepair(before);
        context.setScoreAfterRepair(before);

        if(knowledgeProfileRepairService.shouldRepair(context.getValidationResult())) {
            context.setRepairAttempt(1);
            context.setRepairReason(issueCodes(context.getValidationResult().getIssues()));
            KnowledgeProfileDraft repaired = pipelineEventService.executeStage(context,SpaceConstant.KNOWLEDGE_PIPELINE_REPAIR_PROFILE,
                    context.getRepairReason(),() -> knowledgeProfileRepairService.repair(context.getProfile(),context.getDocument(),context.getChunks()));
            context.setProfile(knowledgeProfileNormalizer.normalize(repaired,context.getDocument()));
            KnowledgeProfileValidationResult repairedValidation = knowledgeProfileValidator.validate(context.getProfile());
            context.setValidationResult(repairedValidation);
            KnowledgeProfileQualityResult after = pipelineEventService.executeStage(context,SpaceConstant.KNOWLEDGE_PIPELINE_SCORE_PROFILE,
                    "afterRepair",() -> knowledgeProfileQualityService.evaluate(context.getProfile(),context.getValidationResult(),
                            context.getChunks(),profileContext.totalContentChunkCount()));
            context.setScoreAfterRepair(after);
        }

        updateRunningFlow(context.getTaskId(),SpaceConstant.KNOWLEDGE_TASK_RUNNING,SpaceConstant.KNOWLEDGE_PIPELINE_SAVE_PROFILE,80,1,0,0,null,null,null);
        SpaceKnowledgeDocumentProfileVO profile = pipelineEventService.executeStage(context,SpaceConstant.KNOWLEDGE_PIPELINE_SAVE_PROFILE,
                "score=" + context.getScoreAfterRepair().getTotalScore(),() -> saveProfile(context,profileContext,sourceSnapshot));
        if(SpaceConstant.KNOWLEDGE_PROFILE_NEEDS_REVIEW.equals(profile.getProfileStatus())) {
            pipelineEventService.reviewRequired(context,profile.getReviewReason());
        }
        return profile;
    }

    private GeneratedProfileContext buildProfileContext(SpaceRagDocument document) {
        List<FileRagChunk> chunks = chunksForDocument(document);
        if(chunks.isEmpty()) {
            throw new BaseException("document has no active chunks");
        }
        List<FileRagChunk> usedChunks = new ArrayList<>();
        String context = buildContext(chunks,usedChunks);
        return new GeneratedProfileContext(chunks,usedChunks,context,totalContentChunkCount(chunks));
    }

    private SpaceKnowledgeDocumentProfileVO saveProfile(KnowledgePipelineContext context,
                                                         GeneratedProfileContext profileContext,
                                                         KnowledgeSourceSnapshot sourceSnapshot) {
        SpaceRagDocument document = context.getDocument();
        KnowledgeProfileDraft generated = context.getProfile();
        KnowledgeProfileQualityResult quality = context.getScoreAfterRepair();
        boolean needsReview = SpaceConstant.KNOWLEDGE_PROFILE_NEEDS_REVIEW.equals(quality.getProfileStatus());
        SpaceKnowledgeDocumentProfile profile = new SpaceKnowledgeDocumentProfile();
        profile.setSpaceId(document.getSpaceId());
        profile.setDocumentId(document.getId());
        profile.setSpaceFileId(document.getSpaceFileId());
        profile.setTitle(blankToDefault(generated.getTitle(),document.getFileName()));
        profile.setSummary(blankToDefault(generated.getSummary(),profileContext.context().substring(0,Math.min(profileContext.context().length(),300))));
        profile.setKeywordsJson(toJson(generated.getKeywords()));
        profile.setTagsJson(toJson(generated.getTags()));
        profile.setCategory(blankToDefault(generated.getCategory(),"uncategorized"));
        profile.setLanguage(blankToDefault(generated.getLanguage(),"unknown"));
        profile.setDocumentType(blankToDefault(generated.getDocumentType(),document.getFileType()));
        profile.setQualityScore(BigDecimal.valueOf(quality.getTotalScore()).setScale(2,RoundingMode.HALF_UP));
        profile.setProfileStatus(needsReview ? SpaceConstant.KNOWLEDGE_PROFILE_NEEDS_REVIEW : SpaceConstant.KNOWLEDGE_PROFILE_VALID);
        profile.setRawLlmOutput(context.getRawLlmOutput());
        profile.setNormalizedProfileJson(toJsonObject(generated));
        profile.setQualityDetailJson(toJsonObject(quality.getDimensionScores()));
        profile.setQualityIssueJson(toJsonObject(quality.getIssues()));
        profile.setScoreBeforeRepair(BigDecimal.valueOf(context.getScoreBeforeRepair().getTotalScore()).setScale(2,RoundingMode.HALF_UP));
        profile.setScoreAfterRepair(BigDecimal.valueOf(quality.getTotalScore()).setScale(2,RoundingMode.HALF_UP));
        profile.setReviewStatus(needsReview ? SpaceConstant.KNOWLEDGE_REVIEW_PENDING : SpaceConstant.KNOWLEDGE_REVIEW_NOT_REQUIRED);
        profile.setReviewReason(needsReview ? issueCodes(quality.getIssues()) : null);
        profile.setSourceChunkIds(sourceSnapshot.sourceChunkIds());
        profile.setSourceChunkCount(sourceSnapshot.sourceChunkCount());
        profile.setSourceCharacterCount(sourceSnapshot.sourceCharacterCount());
        profile.setSourceSnapshotSignature(sourceSnapshot.signature());
        profile.setSourceSnapshotRevision(1L);
        profile.setSchemaValid(generated.isSchemaValid());
        profile.setRepairAttempt(context.getRepairAttempt());
        profile.setRepairReason(context.getRepairReason());
        profile.setProfileVersion(1);
        profile.setSourceFileHash(document.getFileHash());
        profile.setSourceParserVersion(knowledgePipelineIncrementalService.parserVersion());
        profile.setProfileSchemaVersion(KnowledgePipelineIncrementalService.PROFILE_SCHEMA_VERSION);
        profile.setErrorMessage(null);
        profile.setStatus(StatusConstant.ENABLE);
        profile.setCreatetime(LocalDateTime.now());
        return toProfileVO(knowledgeProfileWriteService.saveProfile(profile,generated.getQuestions()));
    }

    private String generateProfileRaw(SpaceRagDocument document, String context) {
        RagGenerateRequest request = new RagGenerateRequest();
        request.setModel(ragProperties.getQuery().getModel());
        request.setTemperature(0.1);
        request.setMaxTokens(1200);
        request.setSystemPrompt("You extract structured knowledge metadata. Return valid JSON only.");
        request.setPrompt("Analyze this Space document and return JSON with keys: " +
                "title, summary, keywords, tags, category, language, documentType, questions. " +
                "keywords/tags/questions must be arrays of short strings. Keep summary under 180 Chinese characters if the document is Chinese. " +
                "File name: " + document.getFileName() + "\nFile type: " + document.getFileType() + "\nContent:\n" + context);
        try {
            RagGenerateResponse response = ragModelClient.generate(request);
            if(response != null && response.getText() != null && !response.getText().isBlank()) {
                return response.getText();
            }
        } catch (Exception ignored) {
            // Fall back to deterministic metadata when generation is unavailable.
        }
        return toJsonObject(fallbackDraft(document,context));
    }

    private List<FileRagChunk> chunksForDocument(SpaceRagDocument document) {
        if(document.getFileHash() != null && !document.getFileHash().isBlank()) {
            return fileRagChunkMapper.listByFileUuidAndHash(document.getFileUuid(),document.getFileHash());
        }
        return fileRagChunkMapper.listByFileUuid(document.getFileUuid());
    }

    private KnowledgePipelineContext createPipelineContext(SpaceKnowledgePipelineTask task, SpaceRagDocument document) {
        KnowledgePipelineContext context = new KnowledgePipelineContext();
        context.setTaskId(task.getId());
        context.setSpaceId(task.getSpaceId());
        context.setDocumentId(document.getId());
        context.setDocument(document);
        context.setTraceId("knowledge-" + task.getId());
        context.setAttemptNo(1);
        context.setForceRebuild(Boolean.TRUE.equals(task.getForceRebuild()));
        return context;
    }

    private boolean isEmptyProfile(GeneratedProfile profile) {
        return (profile.title() == null || profile.title().isBlank())
                && (profile.summary() == null || profile.summary().isBlank())
                && (profile.keywords() == null || profile.keywords().isEmpty())
                && (profile.tags() == null || profile.tags().isEmpty())
                && (profile.questions() == null || profile.questions().isEmpty());
    }

    GeneratedProfile parseGeneratedProfile(String text) {
        try {
            JsonNode root = objectMapper.readTree(extractJsonObject(text));
            return new GeneratedProfile(
                    textValue(root,"title"),
                    textValue(root,"summary"),
                    stringArray(root,"keywords",8),
                    stringArray(root,"tags",8),
                    textValue(root,"category"),
                    textValue(root,"language"),
                    textValue(root,"documentType"),
                    stringArray(root,"questions",MAX_QUESTIONS)
            );
        } catch (Exception ex) {
            return new GeneratedProfile(null,null,List.of(),List.of(),null,null,null,List.of());
        }
    }

    private GeneratedProfile fallbackProfile(SpaceRagDocument document, String context) {
        List<String> keywords = simpleKeywords(document.getFileName(),context);
        return new GeneratedProfile(
                document.getFileName(),
                context.substring(0,Math.min(context.length(),300)),
                keywords,
                keywords.subList(0,Math.min(5,keywords.size())),
                extensionCategory(document.getFileType()),
                "unknown",
                blankToDefault(document.getFileType(),"document"),
                List.of("What is the main content of " + document.getFileName() + "?")
        );
    }

    private KnowledgeProfileDraft fallbackDraft(SpaceRagDocument document, String context) {
        List<String> keywords = simpleKeywords(document.getFileName(),context);
        KnowledgeProfileDraft draft = new KnowledgeProfileDraft();
        draft.setTitle(document.getFileName());
        draft.setSummary(context.substring(0,Math.min(context.length(),300)));
        draft.setKeywords(keywords);
        draft.setTags(keywords.subList(0,Math.min(5,keywords.size())));
        draft.setCategory(extensionCategory(document.getFileType()));
        draft.setLanguage("unknown");
        draft.setDocumentType(blankToDefault(document.getFileType(),"document"));
        draft.setQuestions(List.of("What is the main content of " + document.getFileName() + "?"));
        draft.setSchemaValid(false);
        return draft;
    }

    private void saveQuestions(SpaceRagDocument document, List<String> questions) {
        Set<String> deduped = new LinkedHashSet<>();
        for(String question : questions == null ? List.<String>of() : questions) {
            if(question != null && !question.isBlank()) {
                deduped.add(question.trim());
            }
            if(deduped.size() >= MAX_QUESTIONS) {
                break;
            }
        }
        LocalDateTime now = LocalDateTime.now();
        for(String value : deduped) {
            SpaceKnowledgeQuestion question = new SpaceKnowledgeQuestion();
            question.setSpaceId(document.getSpaceId());
            question.setDocumentId(document.getId());
            question.setQuestion(value);
            question.setSource("GENERATED");
            question.setConfidence(BigDecimal.valueOf(0.80));
            question.setStatus(StatusConstant.ENABLE);
            question.setCreatetime(now);
            question.setUpdatetime(now);
            questionMapper.insert(question);
        }
    }

    private SpaceKnowledgePipelineTask createDocumentProfileTask(Long spaceId, Long documentId, Long userId, String stage, boolean forceRebuild) {
        SpaceRagDocument document = requireDocument(spaceId,documentId);
        SpaceKnowledgePipelineTask task = createTask(spaceId,document.getId(),SpaceConstant.KNOWLEDGE_TASK_PROFILE_DOCUMENT,userId,forceRebuild);
        updateFlow(task.getId(),SpaceConstant.KNOWLEDGE_TASK_PENDING,stage,0,1,0,0,null,null,null);
        pipelineEventService.taskCreated(task.getId(),spaceId,document.getId(),"knowledge-" + task.getId());
        return taskMapper.getById(task.getId());
    }

    private void saveFailedProfile(SpaceRagDocument document, String errorMessage) {
        SpaceKnowledgeDocumentProfile profile = new SpaceKnowledgeDocumentProfile();
        profile.setSpaceId(document.getSpaceId());
        profile.setDocumentId(document.getId());
        profile.setSpaceFileId(document.getSpaceFileId());
        profile.setTitle(document.getFileName());
        profile.setSummary(null);
        profile.setKeywordsJson("[]");
        profile.setTagsJson("[]");
        profile.setCategory("uncategorized");
        profile.setLanguage("unknown");
        profile.setDocumentType(blankToDefault(document.getFileType(),"document"));
        profile.setQualityScore(BigDecimal.ZERO.setScale(2,RoundingMode.HALF_UP));
        profile.setProfileStatus(SpaceConstant.KNOWLEDGE_PROFILE_INVALID);
        profile.setReviewStatus(SpaceConstant.KNOWLEDGE_REVIEW_PENDING);
        profile.setReviewReason("PIPELINE_FAILED");
        profile.setScoreBeforeRepair(BigDecimal.ZERO.setScale(2,RoundingMode.HALF_UP));
        profile.setScoreAfterRepair(BigDecimal.ZERO.setScale(2,RoundingMode.HALF_UP));
        profile.setSourceChunkCount(0);
        profile.setSourceCharacterCount(0);
        profile.setSchemaValid(false);
        profile.setRepairAttempt(0);
        profile.setProfileVersion(1);
        profile.setSourceFileHash(document.getFileHash());
        profile.setSourceParserVersion(knowledgePipelineIncrementalService.parserVersion());
        profile.setProfileSchemaVersion(KnowledgePipelineIncrementalService.PROFILE_SCHEMA_VERSION);
        profile.setErrorMessage(errorMessage);
        profile.setStatus(StatusConstant.ENABLE);
        profile.setCreatetime(LocalDateTime.now());
        knowledgeProfileWriteService.saveFailedProfile(profile);
    }

    private SpaceRagDocument requireDocument(Long spaceId, Long documentId) {
        SpaceRagDocument document = spaceRagDocumentMapper.getById(documentId);
        if(document == null || !spaceId.equals(document.getSpaceId())) {
            throw new BaseException("knowledge document not found");
        }
        if(!SpaceConstant.RAG_INDEX_SUCCESS.equals(document.getIndexStatus())) {
            throw new BaseException("document must finish RAG indexing before knowledge pipeline");
        }
        return document;
    }

    private SpaceKnowledgePipelineTask createTask(Long spaceId, Long documentId, String taskType, Long userId, boolean forceRebuild) {
        LocalDateTime now = LocalDateTime.now();
        SpaceKnowledgePipelineTask task = new SpaceKnowledgePipelineTask();
        task.setSpaceId(spaceId);
        task.setDocumentId(documentId);
        task.setTaskType(taskType);
        task.setTaskStatus(SpaceConstant.KNOWLEDGE_TASK_PENDING);
        task.setStage(SpaceConstant.KNOWLEDGE_STAGE_PENDING);
        task.setProgress(0);
        task.setTotalCount(0);
        task.setSuccessCount(0);
        task.setFailedCount(0);
        task.setErrorMessage(null);
        task.setForceRebuild(forceRebuild);
        task.setTerminalStage(null);
        task.setTerminalReason(null);
        task.setIncrementalAction(null);
        task.setIncrementalDetail(null);
        task.setCreatedBy(userId);
        task.setCreatetime(now);
        task.setUpdatetime(now);
        try {
            taskMapper.insert(task);
            return task;
        } catch (DuplicateKeyException ex) {
            SpaceKnowledgePipelineTask active = documentId == null
                    ? taskMapper.getActiveSpaceTask(spaceId)
                    : taskMapper.getActiveDocumentTask(spaceId,documentId);
            if(active != null) {
                return active;
            }
            throw ex;
        }
    }

    private void requireKnowledgeProfileEnabled(Long spaceId) {
        if(!isKnowledgeProfileEnabled(spaceId)) {
            throw new BaseException("知识画像功能已关闭，请先在检索设置中开启");
        }
    }

    private boolean isKnowledgeProfileEnabled(Long spaceId) {
        if(spaceRagMapper == null) {
            return true;
        }
        SpaceRagConfig config = spaceRagMapper.getBySpaceId(spaceId);
        if(config == null) {
            throw new BaseException("空间 RAG 配置不存在");
        }
        return !StatusConstant.DISABLE.equals(config.getKnowledgeProfileEnabled());
    }

    private void markTaskSkipped(SpaceKnowledgePipelineTask task, String reason) {
        LocalDateTime finished = LocalDateTime.now();
        updateFlow(task.getId(),SpaceConstant.KNOWLEDGE_TASK_SKIPPED,SpaceConstant.KNOWLEDGE_STAGE_SKIPPED,
                100,task.getDocumentId() == null ? 0 : 1,0,0,null,task.getStartedTime(),finished);
        taskMapper.updateIncremental(task.getId(),SpaceConstant.KNOWLEDGE_STAGE_SKIPPED,reason,
                null,null,finished);
    }

    private void markRunningTaskSkipped(SpaceKnowledgePipelineTask task, String reason) {
        LocalDateTime finished = LocalDateTime.now();
        updateRunningFlow(task.getId(),SpaceConstant.KNOWLEDGE_TASK_SKIPPED,SpaceConstant.KNOWLEDGE_STAGE_SKIPPED,
                100,task.getDocumentId() == null ? 0 : 1,0,0,null,task.getStartedTime(),finished);
        taskMapper.updateIncremental(task.getId(),SpaceConstant.KNOWLEDGE_STAGE_SKIPPED,reason,
                null,null,finished);
    }

    static String spaceTaskStatus(int success, int failed) {
        if(failed == 0) {
            return success == 0 ? SpaceConstant.KNOWLEDGE_TASK_SKIPPED : SpaceConstant.KNOWLEDGE_TASK_SUCCESS;
        }
        return success == 0 ? SpaceConstant.KNOWLEDGE_TASK_FAILED : SpaceConstant.KNOWLEDGE_TASK_PARTIAL_SUCCESS;
    }

    private void updateFlow(Long taskId, String status, String stage, int progress, int totalCount, int successCount, int failedCount,
                            String errorMessage, LocalDateTime startedTime, LocalDateTime finishedTime) {
        taskMapper.updateFlow(taskId,status,stage,progress,totalCount,successCount,failedCount,errorMessage,startedTime,finishedTime,LocalDateTime.now());
    }

    private int updateRunningFlow(Long taskId, String status, String stage, int progress, int totalCount, int successCount, int failedCount,
                                  String errorMessage, LocalDateTime startedTime, LocalDateTime finishedTime) {
        return taskMapper.updateFlowIfRunning(taskId,status,stage,progress,totalCount,successCount,failedCount,
                errorMessage,startedTime,finishedTime,LocalDateTime.now());
    }

    private String buildContext(List<FileRagChunk> chunks, List<FileRagChunk> usedChunks) {
        StringBuilder builder = new StringBuilder();
        for(FileRagChunk chunk : chunks) {
            if(chunk.getContent() == null || chunk.getContent().isBlank()) {
                continue;
            }
            if(builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append(chunk.getContent());
            usedChunks.add(chunk);
            if(builder.length() >= MAX_CONTEXT_CHARS) {
                break;
            }
        }
        String context = builder.toString();
        return context.length() > MAX_CONTEXT_CHARS ? context.substring(0,MAX_CONTEXT_CHARS) : context;
    }

    private int totalContentChunkCount(List<FileRagChunk> chunks) {
        int count = 0;
        for(FileRagChunk chunk : chunks == null ? List.<FileRagChunk>of() : chunks) {
            if(chunk.getContent() == null || chunk.getContent().isBlank()) {
                continue;
            }
            String metadata = chunk.getMetadata() == null ? "" : chunk.getMetadata().toLowerCase();
            if(metadata.contains("metadata_only") || metadata.contains("metadata-only")
                    || metadata.contains("\"parser\":\"metadata\"") || metadata.contains("\"parser\": \"metadata\"")) {
                continue;
            }
            count++;
        }
        return count;
    }

    private BigDecimal qualityScore(SpaceRagDocument document, List<FileRagChunk> chunks, GeneratedProfile generated) {
        double score = 50.0;
        if(SpaceConstant.RAG_INDEX_SUCCESS.equals(document.getIndexStatus())) score += 15.0;
        if(!chunks.isEmpty()) score += 10.0;
        if(generated.summary() != null && !generated.summary().isBlank()) score += 10.0;
        if(generated.tags() != null && !generated.tags().isEmpty()) score += 7.5;
        if(generated.questions() != null && !generated.questions().isEmpty()) score += 7.5;
        return BigDecimal.valueOf(Math.min(100.0,score)).setScale(2,RoundingMode.HALF_UP);
    }

    private SpaceKnowledgeDocumentProfileVO toProfileVO(SpaceKnowledgeDocumentProfile profile) {
        SpaceKnowledgeDocumentProfileVO vo = new SpaceKnowledgeDocumentProfileVO();
        vo.setId(profile.getId());
        vo.setSpaceId(profile.getSpaceId());
        vo.setDocumentId(profile.getDocumentId());
        vo.setSpaceFileId(profile.getSpaceFileId());
        vo.setTitle(profile.getTitle());
        vo.setSummary(profile.getSummary());
        vo.setKeywords(fromJsonArray(profile.getKeywordsJson()));
        vo.setTags(fromJsonArray(profile.getTagsJson()));
        vo.setCategory(profile.getCategory());
        vo.setLanguage(profile.getLanguage());
        vo.setDocumentType(profile.getDocumentType());
        vo.setQualityScore(profile.getQualityScore());
        vo.setProfileStatus(profile.getProfileStatus());
        vo.setQualityDetailJson(profile.getQualityDetailJson());
        vo.setQualityIssueJson(profile.getQualityIssueJson());
        vo.setScoreBeforeRepair(profile.getScoreBeforeRepair());
        vo.setScoreAfterRepair(profile.getScoreAfterRepair());
        vo.setReviewStatus(profile.getReviewStatus());
        vo.setReviewReason(profile.getReviewReason());
        vo.setSourceChunkCount(profile.getSourceChunkCount());
        vo.setSourceCharacterCount(profile.getSourceCharacterCount());
        vo.setSourceSnapshotSignature(profile.getSourceSnapshotSignature());
        vo.setSourceSnapshotRevision(profile.getSourceSnapshotRevision());
        vo.setSchemaValid(profile.getSchemaValid());
        vo.setRepairAttempt(profile.getRepairAttempt());
        vo.setRepairReason(profile.getRepairReason());
        vo.setProfileVersion(profile.getProfileVersion());
        vo.setCurrentVersionId(profile.getCurrentVersionId());
        vo.setLatestVersionId(profile.getLatestVersionId());
        vo.setSourceFileHash(profile.getSourceFileHash());
        vo.setSourceParserVersion(profile.getSourceParserVersion());
        vo.setProfileSchemaVersion(profile.getProfileSchemaVersion());
        vo.setErrorMessage(profile.getErrorMessage());
        List<String> questions = new ArrayList<>();
        for(SpaceKnowledgeQuestion question : questionMapper.listByDocumentId(profile.getSpaceId(),profile.getDocumentId())) {
            questions.add(question.getQuestion());
        }
        vo.setQuestions(questions);
        vo.setCreatetime(profile.getCreatetime());
        vo.setUpdatetime(profile.getUpdatetime());
        return vo;
    }

    private SpaceKnowledgePipelineTaskVO toTaskVO(SpaceKnowledgePipelineTask task) {
        SpaceKnowledgePipelineTaskVO vo = new SpaceKnowledgePipelineTaskVO();
        vo.setId(task.getId());
        vo.setSpaceId(task.getSpaceId());
        vo.setDocumentId(task.getDocumentId());
        vo.setTaskType(task.getTaskType());
        vo.setTaskStatus(task.getTaskStatus());
        vo.setStage(task.getStage());
        vo.setProgress(task.getProgress());
        vo.setTotalCount(task.getTotalCount());
        vo.setSuccessCount(task.getSuccessCount());
        vo.setFailedCount(task.getFailedCount());
        vo.setErrorMessage(task.getErrorMessage());
        vo.setForceRebuild(task.getForceRebuild());
        vo.setTerminalStage(task.getTerminalStage());
        vo.setTerminalReason(task.getTerminalReason());
        vo.setIncrementalAction(task.getIncrementalAction());
        vo.setIncrementalDetail(task.getIncrementalDetail());
        vo.setCreatedBy(task.getCreatedBy());
        vo.setStartedTime(task.getStartedTime());
        vo.setFinishedTime(task.getFinishedTime());
        vo.setCreatetime(task.getCreatetime());
        vo.setUpdatetime(task.getUpdatetime());
        return vo;
    }

    private SpaceKnowledgeDocumentItemVO toDocumentItemVO(SpaceRagDocument document, SpaceKnowledgeDocumentProfile profile) {
        SpaceKnowledgeDocumentItemVO vo = new SpaceKnowledgeDocumentItemVO();
        vo.setDocumentId(document.getId());
        vo.setSpaceId(document.getSpaceId());
        vo.setSpaceFileId(document.getSpaceFileId());
        vo.setFileUuid(document.getFileUuid());
        vo.setFileName(document.getFileName());
        vo.setFileType(document.getFileType());
        vo.setIndexStatus(document.getIndexStatus());
        vo.setChunkCount(document.getChunkCount());
        vo.setUpdatetime(document.getUpdatetime());
        if(profile != null) {
            vo.setProfileStatus(profile.getProfileStatus());
            vo.setTitle(profile.getTitle());
            vo.setSummary(profile.getSummary());
            vo.setCategory(profile.getCategory());
            vo.setTags(fromJsonArray(profile.getTagsJson()));
            vo.setKeywords(fromJsonArray(profile.getKeywordsJson()));
            vo.setQualityScore(profile.getQualityScore());
            vo.setReviewStatus(profile.getReviewStatus());
            vo.setReviewReason(profile.getReviewReason());
            vo.setQualityIssueJson(profile.getQualityIssueJson());
            vo.setSourceChunkCount(profile.getSourceChunkCount());
            vo.setRepairAttempt(profile.getRepairAttempt());
            vo.setErrorMessage(profile.getErrorMessage());
            vo.setUpdatetime(profile.getUpdatetime());
        } else {
            vo.setProfileStatus(SpaceConstant.KNOWLEDGE_PROFILE_PENDING);
            vo.setTitle(document.getFileName());
            vo.setTags(List.of());
            vo.setKeywords(List.of());
            vo.setCategory("uncategorized");
            vo.setErrorMessage(document.getErrorMessage());
        }
        return vo;
    }

    private BigDecimal averageQuality(List<SpaceKnowledgeDocumentProfile> profiles) {
        List<BigDecimal> scores = profiles.stream()
                .map(SpaceKnowledgeDocumentProfile::getQualityScore)
                .filter(score -> score != null)
                .toList();
        if(scores.isEmpty()) {
            return BigDecimal.ZERO.setScale(2,RoundingMode.HALF_UP);
        }
        BigDecimal total = scores.stream().reduce(BigDecimal.ZERO,BigDecimal::add);
        return total.divide(BigDecimal.valueOf(scores.size()),2,RoundingMode.HALF_UP);
    }

    private List<SpaceKnowledgeFacetVO> categoryFacets(List<SpaceKnowledgeDocumentProfile> profiles) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for(SpaceKnowledgeDocumentProfile profile : profiles) {
            String category = blankToDefault(profile.getCategory(),"uncategorized");
            counts.put(category,counts.getOrDefault(category,0) + 1);
        }
        return toFacetList(counts);
    }

    private List<SpaceKnowledgeFacetVO> tagFacets(List<SpaceKnowledgeDocumentProfile> profiles) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for(SpaceKnowledgeDocumentProfile profile : profiles) {
            for(String tag : fromJsonArray(profile.getTagsJson())) {
                if(tag == null || tag.isBlank()) {
                    continue;
                }
                counts.put(tag,counts.getOrDefault(tag,0) + 1);
            }
        }
        return toFacetList(counts);
    }

    private List<SpaceKnowledgeFacetVO> toFacetList(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .map(entry -> new SpaceKnowledgeFacetVO(entry.getKey(),entry.getValue()))
                .sorted(Comparator.comparing(SpaceKnowledgeFacetVO::getCount).reversed()
                        .thenComparing(SpaceKnowledgeFacetVO::getName))
                .toList();
    }

    private String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if(start >= 0 && end > start) {
            return text.substring(start,end + 1);
        }
        return text;
    }

    private String textValue(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private List<String> stringArray(JsonNode root, String field, int limit) {
        JsonNode node = root.get(field);
        List<String> values = new ArrayList<>();
        if(node != null && node.isArray()) {
            for(JsonNode item : node) {
                if(item != null && !item.asText().isBlank()) {
                    values.add(item.asText().trim());
                }
                if(values.size() >= limit) {
                    break;
                }
            }
        }
        return values;
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private String toJsonObject(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private String issueCodes(List<QualityIssue> issues) {
        if(issues == null || issues.isEmpty()) {
            return null;
        }
        return issues.stream()
                .map(QualityIssue::code)
                .distinct()
                .limit(8)
                .reduce((left,right) -> left + "," + right)
                .orElse(null);
    }

    private List<String> fromJsonArray(String json) {
        if(json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if(!node.isArray()) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            for(JsonNode item : node) {
                values.add(item.asText());
            }
            return values;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private List<String> simpleKeywords(String fileName, String context) {
        Set<String> result = new LinkedHashSet<>();
        for(String value : (fileName + " " + context).split("[\\s,.;:()\\[\\]{}<>/\\\\|\"']+")) {
            String token = value.trim();
            if(token.length() >= 2 && token.length() <= 30) {
                result.add(token);
            }
            if(result.size() >= 8) {
                break;
            }
        }
        return new ArrayList<>(result);
    }

    private String extensionCategory(String fileType) {
        if(fileType == null || fileType.isBlank()) {
            return "document";
        }
        String type = fileType.toLowerCase();
        if(type.contains("pdf") || type.contains("doc")) return "document";
        if(type.contains("md") || type.contains("txt")) return "text";
        if(type.contains("java") || type.contains("js") || type.contains("sql")) return "code";
        return "document";
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String safeError(Exception ex) {
        String message = ex.getMessage();
        if(message == null || message.isBlank()) {
            message = ex.getClass().getSimpleName();
        }
        return message.length() > 900 ? message.substring(0,900) : message;
    }

    private String profileStatus(List<FileRagChunk> chunks, GeneratedProfile generated) {
        if(chunks == null || chunks.isEmpty()) {
            return SpaceConstant.KNOWLEDGE_PROFILE_FAILED;
        }
        if(generated.summary() == null || generated.summary().isBlank()
                || generated.tags() == null || generated.tags().isEmpty()
                || generated.questions() == null || generated.questions().isEmpty()) {
            return SpaceConstant.KNOWLEDGE_PROFILE_NEEDS_REVIEW;
        }
        return SpaceConstant.KNOWLEDGE_PROFILE_SUCCESS;
    }

    record GeneratedProfileContext(List<FileRagChunk> chunks,
                                   List<FileRagChunk> usedChunks,
                                   String context,
                                   int totalContentChunkCount) {
    }

    record GeneratedProfile(String title,
                            String summary,
                            List<String> keywords,
                            List<String> tags,
                            String category,
                            String language,
                            String documentType,
                            List<String> questions) {
    }
}
