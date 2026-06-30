package com.ylcloud.service;

import com.ylcloud.constant.StatusConstant;
import com.ylcloud.entity.SpaceKnowledgeDocumentProfile;
import com.ylcloud.entity.SpaceKnowledgeQuestion;
import com.ylcloud.mapper.SpaceKnowledgeDocumentProfileMapper;
import com.ylcloud.mapper.SpaceKnowledgeQuestionMapper;
import org.springframework.stereotype.Service;
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

    public KnowledgeProfileWriteService(SpaceKnowledgeDocumentProfileMapper profileMapper,
                                        SpaceKnowledgeQuestionMapper questionMapper,
                                        KnowledgeProfileAssetService assetService) {
        this.profileMapper = profileMapper;
        this.questionMapper = questionMapper;
        this.assetService = assetService;
    }

    @Transactional
    public SpaceKnowledgeDocumentProfile saveProfile(SpaceKnowledgeDocumentProfile profile, List<String> questions) {
        LocalDateTime now = LocalDateTime.now();
        if(profile.getCreatetime() == null) {
            profile.setCreatetime(now);
        }
        profile.setUpdatetime(now);
        profileMapper.upsert(profile);
        questionMapper.deleteByDocumentId(profile.getSpaceId(),profile.getDocumentId());
        saveQuestions(profile.getSpaceId(),profile.getDocumentId(),questions);
        SpaceKnowledgeDocumentProfile saved = profileMapper.getByDocumentId(profile.getSpaceId(),profile.getDocumentId());
        String sourceType = saved.getRepairAttempt() != null && saved.getRepairAttempt() > 0 ? "LOCAL_REPAIRED" : "LLM_GENERATED";
        assetService.createVersion(saved,questions,sourceType,null,"Pipeline generated profile");
        return profileMapper.getByDocumentId(profile.getSpaceId(),profile.getDocumentId());
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
        profileMapper.upsert(profile);
        questionMapper.deleteByDocumentId(profile.getSpaceId(),profile.getDocumentId());
        SpaceKnowledgeDocumentProfile saved = profileMapper.getByDocumentId(profile.getSpaceId(),profile.getDocumentId());
        assetService.createVersion(saved,List.of(),"PIPELINE_FAILED",null,"Pipeline failed profile");
    }

    @Transactional
    public SpaceKnowledgeDocumentProfile updateProfile(Long spaceId, Long documentId, String title, String summary,
                                                       String keywordsJson, String tagsJson, String category,
                                                       String profileStatus, List<String> questions, Long operatorId) {
        SpaceKnowledgeDocumentProfile before = profileMapper.getByDocumentId(spaceId,documentId);
        String beforeSnapshot = before == null ? null : assetService.snapshot(before,questionsFor(spaceId,documentId));
        profileMapper.updateManual(spaceId,documentId,title,summary,keywordsJson,tagsJson,category,profileStatus,null,LocalDateTime.now());
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
