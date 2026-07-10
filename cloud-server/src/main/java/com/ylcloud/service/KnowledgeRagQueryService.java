package com.ylcloud.service;

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
    private final SpaceRagService spaceRagService;
    private final SpaceMapper spaceMapper;

    public KnowledgeRagQueryService(SpaceRagService spaceRagService, SpaceMapper spaceMapper) {
        this.spaceRagService = spaceRagService;
        this.spaceMapper = spaceMapper;
    }

    @Transactional
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
            answerJoiner.add("【" + space.getName() + "】\n" + safeAnswer(result.getAnswer()));
        }

        KnowledgeRagQueryVO vo = new KnowledgeRagQueryVO();
        vo.setQuestion(dto.getQuestion());
        vo.setAnswer(answerJoiner.toString());
        vo.setSpaceIds(spaceIds);
        vo.setResults(results);
        vo.setCitations(citations);
        return vo;
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
}
