package com.ylcloud.service;

import com.ylcloud.Exception.ConflictException;
import com.ylcloud.constant.StatusConstant;
import com.ylcloud.entity.SpaceKnowledgeDocumentProfile;
import com.ylcloud.entity.SpaceKnowledgeQuestion;
import com.ylcloud.entity.SpaceRagDocument;
import com.ylcloud.mapper.SpaceKnowledgeDocumentProfileMapper;
import com.ylcloud.mapper.SpaceKnowledgeQuestionMapper;
import com.ylcloud.mapper.SpaceRagDocumentMapper;
import com.ylcloud.mapper.SpaceKnowledgePipelineTaskMapper;
import com.ylcloud.async.task.UnifiedAsyncTaskMapper;
import com.ylcloud.entity.SpaceKnowledgePipelineTask;
import com.ylcloud.entity.UnifiedAsyncTask;
import com.ylcloud.async.task.StaleTaskException;
import com.ylcloud.service.knowledge.pipeline.KnowledgeSourceSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class KnowledgeProfileWriteService {
    private static final int MAX_QUESTIONS = 8;

    private final SpaceKnowledgeDocumentProfileMapper profileMapper;
    private final SpaceKnowledgeQuestionMapper questionMapper;
    private final KnowledgeProfileAssetService assetService;
    private SpaceRagDocumentMapper documentMapper;
    private SpaceKnowledgePipelineTaskMapper pipelineTaskMapper;
    private UnifiedAsyncTaskMapper asyncTaskMapper;

    public KnowledgeProfileWriteService(SpaceKnowledgeDocumentProfileMapper profileMapper,
                                        SpaceKnowledgeQuestionMapper questionMapper,
                                        KnowledgeProfileAssetService assetService) {
        this.profileMapper = profileMapper;
        this.questionMapper = questionMapper;
        this.assetService = assetService;
    }

    @Transactional
    public SpaceKnowledgeDocumentProfile saveProfile(SpaceKnowledgeDocumentProfile profile, List<String> questions) {
        return saveProfile(profile,questions,null,null);
    }

    @Transactional
    public SpaceKnowledgeDocumentProfile saveProfile(SpaceKnowledgeDocumentProfile profile,List<String> questions,
                                                      String modelName,String promptVersion) {
        LocalDateTime now = LocalDateTime.now();
        if(profile.getCreatetime() == null) {
            profile.setCreatetime(now);
        }
        profile.setUpdatetime(now);
        SpaceKnowledgeDocumentProfile current = profileMapper.getByDocumentIdForUpdate(profile.getSpaceId(),profile.getDocumentId());
        boolean active = "VALID".equals(profile.getProfileStatus()) || "SUCCESS".equals(profile.getProfileStatus());
        if(active || current == null || current.getCurrentVersionId() == null) {
            profileMapper.upsert(profile);
        } else {
            profile.setId(current.getId());
        }
        if(active) {
            questionMapper.deleteByDocumentId(profile.getSpaceId(),profile.getDocumentId());
            saveQuestions(profile.getSpaceId(),profile.getDocumentId(),questions);
        }
        SpaceKnowledgeDocumentProfile saved = profileMapper.getByDocumentId(profile.getSpaceId(),profile.getDocumentId());
        profile.setId(saved.getId());
        String sourceType = profile.getRepairAttempt() != null && profile.getRepairAttempt() > 0 ? "LOCAL_REPAIRED" : "LLM_GENERATED";
        assetService.createVersion(profile,questions,sourceType,modelName,promptVersion,null,"Pipeline generated profile");
        return profileMapper.getByDocumentId(profile.getSpaceId(),profile.getDocumentId());
    }

    @Transactional
    public SpaceKnowledgeDocumentProfile saveProfileFenced(SpaceKnowledgeDocumentProfile profile,List<String> questions,
                                                            String modelName,String promptVersion,
                                                            Long pipelineTaskId,Long asyncTaskId,
                                                            long expectedVersion,int expectedAttemptVersion,
                                                            String expectedFileHash) {
        requireAsyncFence(pipelineTaskId,asyncTaskId,expectedVersion,expectedAttemptVersion);
        requireDocumentVersion(profile.getDocumentId(),expectedVersion,expectedFileHash);
        return saveProfile(profile,questions,modelName,promptVersion);
    }

    @Transactional
    public void saveFailedProfile(SpaceKnowledgeDocumentProfile profile) {
        LocalDateTime now = LocalDateTime.now();
        if(profile.getQualityScore() == null) {
            profile.setQualityScore(BigDecimal.ZERO);
        }
        if(profile.getCreatetime() == null) {
            profile.setCreatetime(now);
        }
        profile.setUpdatetime(now);
        SpaceKnowledgeDocumentProfile current = profileMapper.getByDocumentIdForUpdate(profile.getSpaceId(),profile.getDocumentId());
        if(current == null || current.getCurrentVersionId() == null) {
            profileMapper.upsert(profile);
            current = profileMapper.getByDocumentId(profile.getSpaceId(),profile.getDocumentId());
        }
        profile.setId(current.getId());
        assetService.createVersion(profile,List.of(),"PIPELINE_FAILED",null,"Pipeline failed profile");
    }

    @Transactional
    public SpaceKnowledgeDocumentProfile updateProfile(Long spaceId, Long documentId, String title, String summary,
                                                       String keywordsJson, String tagsJson, String category,
                                                       String profileStatus, List<String> questions, Long operatorId) {
        SpaceKnowledgeDocumentProfile before = profileMapper.getByDocumentId(spaceId,documentId);
        String beforeSnapshot = before == null ? null : assetService.snapshot(before,questionsFor(spaceId,documentId));
        profileMapper.updateManual(spaceId,documentId,title,summary,keywordsJson,tagsJson,category,"VALID",null,LocalDateTime.now());
        if(questions != null) {
            questionMapper.deleteByDocumentId(spaceId,documentId);
            saveQuestions(spaceId,documentId,questions);
        }
        SpaceKnowledgeDocumentProfile updated = profileMapper.getByDocumentId(spaceId,documentId);
        List<String> versionQuestions = questions == null ? questionsFor(spaceId,documentId) : questions;
        assetService.createVersion(updated,versionQuestions,"HUMAN_EDITED",operatorId,"Manual profile update");
        assetService.audit(spaceId,operatorId,"PROFILE_EDIT","KNOWLEDGE_PROFILE",updated.getId(),beforeSnapshot,assetService.snapshot(updated,versionQuestions));
        return profileMapper.getByDocumentId(spaceId,documentId);
    }

    /**
     * 切片集合变化但物理文件和知识画像未变化时，同步检索源快照。
     */
    @Transactional
    public SpaceKnowledgeDocumentProfile syncRetrievalSource(Long spaceId,
                                                              Long documentId,
                                                              KnowledgeSourceSnapshot snapshot,
                                                              Long expectedRevision) {
        SpaceKnowledgeDocumentProfile current = profileMapper.getSourceSnapshotForUpdate(spaceId,documentId);
        if(current == null) {
            throw new IllegalStateException("Knowledge profile is missing while syncing retrieval source");
        }
        if(snapshot.matches(current)) {
            return profileMapper.getByDocumentId(spaceId,documentId);
        }
        long revision = expectedRevision == null ? 0L : expectedRevision;
        if(current.getSourceSnapshotRevision() == null || current.getSourceSnapshotRevision() != revision) {
            throw new ConflictException("Knowledge source snapshot changed concurrently; retry the pipeline task");
        }
        int rows = profileMapper.syncRetrievalSource(spaceId,documentId,snapshot.sourceChunkIds(),snapshot.sourceChunkCount(),
                snapshot.sourceCharacterCount(),snapshot.parserVersion(),snapshot.signature(),revision,LocalDateTime.now());
        if(rows == 0) {
            throw new ConflictException("Knowledge source snapshot changed concurrently; retry the pipeline task");
        }
        return profileMapper.getByDocumentId(spaceId,documentId);
    }

    @Transactional
    public SpaceKnowledgeDocumentProfile syncRetrievalSourceFenced(Long spaceId,Long documentId,
                                                                    KnowledgeSourceSnapshot snapshot,Long expectedRevision,
                                                                    Long pipelineTaskId,Long asyncTaskId,
                                                                    long expectedVersion,int expectedAttemptVersion,
                                                                    String expectedFileHash) {
        requireAsyncFence(pipelineTaskId,asyncTaskId,expectedVersion,expectedAttemptVersion);
        requireDocumentVersion(documentId,expectedVersion,expectedFileHash);
        return syncRetrievalSource(spaceId,documentId,snapshot,expectedRevision);
    }

    @Autowired(required=false)
    public void setDocumentMapper(SpaceRagDocumentMapper documentMapper) {
        this.documentMapper=documentMapper;
    }

    @Autowired(required=false)
    public void setAsyncFenceMappers(SpaceKnowledgePipelineTaskMapper pipelineTaskMapper,
                                     UnifiedAsyncTaskMapper asyncTaskMapper) {
        this.pipelineTaskMapper=pipelineTaskMapper;
        this.asyncTaskMapper=asyncTaskMapper;
    }

    private void requireAsyncFence(Long pipelineTaskId,Long asyncTaskId,long expectedVersion,int expectedAttemptVersion) {
        if(pipelineTaskMapper==null || asyncTaskMapper==null) {
            throw new IllegalStateException("Knowledge pipeline task fence is unavailable");
        }
        UnifiedAsyncTask central=asyncTaskMapper.getByIdForUpdate(asyncTaskId);
        SpaceKnowledgePipelineTask domain=pipelineTaskMapper.getByIdForUpdate(pipelineTaskId);
        long version=domain==null || domain.getResourceVersion()==null ? 1L : domain.getResourceVersion();
        if(central==null || !"RUNNING".equals(central.getStatus()) || central.getCancelRequestedAt()!=null
                || central.getAttemptVersion()==null || central.getAttemptVersion()!=expectedAttemptVersion
                || domain==null || !"RUNNING".equals(domain.getTaskStatus())
                || !asyncTaskId.equals(domain.getAsyncTaskId()) || version!=expectedVersion) {
            throw new StaleTaskException("Knowledge pipeline task changed before profile commit");
        }
    }

    private void requireDocumentVersion(Long documentId,long expectedVersion,String expectedFileHash) {
        if(documentMapper==null) throw new IllegalStateException("Knowledge document version fence is unavailable");
        SpaceRagDocument current=documentMapper.getAnyByIdForUpdate(documentId);
        long version=current==null || current.getConsistencyVersion()==null ? 1L : current.getConsistencyVersion();
        if(current==null || current.getStatus()==null || current.getStatus()!=1
                || !"SUCCESS".equals(current.getIndexStatus()) || version!=expectedVersion
                || !java.util.Objects.equals(expectedFileHash,current.getFileHash())) {
            throw new StaleTaskException("Knowledge document version changed before profile commit");
        }
    }

    private List<String> questionsFor(Long spaceId, Long documentId) {
        return questionMapper.listByDocumentId(spaceId,documentId).stream().map(SpaceKnowledgeQuestion::getQuestion).toList();
    }

    private void saveQuestions(Long spaceId, Long documentId, List<String> questions) {
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
            question.setSpaceId(spaceId);
            question.setDocumentId(documentId);
            question.setQuestion(value);
            question.setSource("GENERATED");
            question.setConfidence(BigDecimal.valueOf(0.80));
            question.setStatus(StatusConstant.ENABLE);
            question.setCreatetime(now);
            question.setUpdatetime(now);
            questionMapper.insert(question);
        }
    }
}
