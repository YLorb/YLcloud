package com.ylcloud.service;

import com.ylcloud.service.space.SpaceRagService;
import com.ylcloud.DTO.KnowledgeRagQueryDTO;
import com.ylcloud.DTO.SpaceRagQueryDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.VO.KnowledgeRagQueryVO;
import com.ylcloud.VO.SpaceRagCitationVO;
import com.ylcloud.VO.SpaceRagQueryVO;
import com.ylcloud.entity.Space;
import com.ylcloud.mapper.SpaceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

@Service
public class KnowledgeRagQueryService {
    private com.ylcloud.service.rag.RagEvidenceService evidenceService;
    @org.springframework.beans.factory.annotation.Autowired
    public void setEvidenceService(com.ylcloud.service.rag.RagEvidenceService service){this.evidenceService=service;}
    private final SpaceRagService spaceRagService;
    private final SpaceMapper spaceMapper;

    public KnowledgeRagQueryService(SpaceRagService spaceRagService, SpaceMapper spaceMapper) {
        this.spaceRagService = spaceRagService;
        this.spaceMapper = spaceMapper;
    }

    public KnowledgeRagQueryVO query(KnowledgeRagQueryDTO dto, Long userId) {
        List<Long> spaceIds = normalizeSpaceIds(dto.getSpaceIds());
        if(spaceIds.isEmpty()) {
            throw new BaseException("至少选择一个知识库");
        }
        if(spaceIds.size() > 5) {
            throw new BaseException("一次最多同时查询 5 个知识库");
        }

        List<SpaceRagQueryVO> results = new ArrayList<>();
        List<SpaceRagCitationVO> citations = new ArrayList<>();
        StringJoiner answerJoiner = new StringJoiner("\n\n");
        int citationIndex = 1;

        for(Long spaceId : spaceIds) {
            Space space = spaceMapper.getById(spaceId);
            if(space == null) {
                throw new BaseException("知识库不存在");
            }
            SpaceRagQueryDTO queryDTO = new SpaceRagQueryDTO();
            queryDTO.setQuestion(dto.getQuestion());
            queryDTO.setRetrievalMode(dto.getRetrievalMode());
            queryDTO.setHistory(dto.getHistory());

            SpaceRagQueryVO result = spaceRagService.query(spaceId,queryDTO,userId);
            result.setSpaceId(spaceId);
            result.setSpaceName(space.getName());
            if(result.getCitations() != null) {
                for(SpaceRagCitationVO citation : result.getCitations()) {
                    citation.setIndex(citationIndex++);
                    citation.setSpaceId(spaceId);
                    citation.setSpaceName(space.getName());
                    citations.add(citation);
                }
            }
            results.add(result);
            answerJoiner.add("【" + space.getName() + "】\n" + renderScopedAnswer(result));
        }

        KnowledgeRagQueryVO vo = new KnowledgeRagQueryVO();
        vo.setQuestion(dto.getQuestion());
        vo.setAnswer(answerJoiner.toString());
        vo.setSpaceIds(spaceIds);
        vo.setResults(results);
        vo.setCitations(citations);
        String status=aggregateStatus(results);
        vo.setAnswerStatus(status);
        if(evidenceService!=null) vo.setAnswerId(evidenceService.storeAnswer(userId,spaceIds,vo.getAnswer(),citations,status));
        return vo;
    }

    private String aggregateStatus(List<SpaceRagQueryVO> results) {
        if(results.stream().allMatch(r -> "ANSWERED".equals(r.getAnswerStatus()))) return "ANSWERED";
        if(results.stream().anyMatch(r -> "ANSWERED".equals(r.getAnswerStatus()) || "PARTIAL".equals(r.getAnswerStatus()))) return "PARTIAL";
        if(results.stream().anyMatch(r -> "UNAVAILABLE".equals(r.getAnswerStatus()))) return "UNAVAILABLE";
        return "REFUSED";
    }

    private List<Long> normalizeSpaceIds(List<Long> spaceIds) {
        Set<Long> unique = new LinkedHashSet<>();
        if(spaceIds != null) {
            for(Long spaceId : spaceIds) {
                if(spaceId != null && spaceId > 0) {
                    unique.add(spaceId);
                }
            }
        }
        return new ArrayList<>(unique);
    }

    private String safeAnswer(String answer) {
        return answer == null || answer.isBlank() ? "无法从当前知识库回答。" : answer;
    }

    private String renderScopedAnswer(SpaceRagQueryVO result) {
        if(result.getClaims()==null || result.getClaims().isEmpty()) return safeAnswer(result.getAnswer());
        java.util.Map<String,Integer> numbers=new java.util.HashMap<>();
        if(result.getCitations()!=null) for(SpaceRagCitationVO citation:result.getCitations())
            numbers.put(citation.getSourceEvidenceId(),citation.getIndex());
        StringJoiner answer=new StringJoiner("\n\n");
        for(com.ylcloud.VO.RagAnswerClaim claim:result.getClaims()) {
            StringJoiner refs=new StringJoiner("");
            for(String sourceId:claim.getEvidenceIds()) {
                Integer index=numbers.get(sourceId);
                if(index==null) throw new BaseException("回答引用关联已失效");
                refs.add("["+index+"]");
            }
            boolean inference="INFERENCE".equals(claim.getType());
            String conditions=claim.getConditions()==null || claim.getConditions().isEmpty() ? ""
                    : "\n适用条件："+String.join("；",claim.getConditions());
            answer.add((inference ? "【基于资料的推断】" : "【资料依据】")+claim.getText()+refs+conditions
                    +(inference ? "\n推断说明："+claim.getReasoning() : ""));
        }
        return answer.toString();
    }
}
