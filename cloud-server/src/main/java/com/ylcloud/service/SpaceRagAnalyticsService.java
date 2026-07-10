package com.ylcloud.service;

import com.ylcloud.VO.SpaceRagAnalyticsSummaryVO;
import com.ylcloud.VO.SpaceRagConfigLogVO;
import com.ylcloud.VO.SpaceRagQueryLogVO;
import com.ylcloud.entity.SpaceRagConfigLog;
import com.ylcloud.entity.SpaceRagQueryLog;
import com.ylcloud.mapper.SpaceRagConfigLogMapper;
import com.ylcloud.mapper.SpaceRagQueryLogMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class SpaceRagAnalyticsService {
    private final SpacePermissionService spacePermissionService;
    private final SpaceRagQueryLogMapper queryLogMapper;
    private final SpaceRagConfigLogMapper configLogMapper;

    public SpaceRagAnalyticsService(SpacePermissionService spacePermissionService,
                                    SpaceRagQueryLogMapper queryLogMapper,
                                    SpaceRagConfigLogMapper configLogMapper) {
        this.spacePermissionService = spacePermissionService;
        this.queryLogMapper = queryLogMapper;
        this.configLogMapper = configLogMapper;
    }

    public SpaceRagAnalyticsSummaryVO summary(Long spaceId, Long userId) {
        spacePermissionService.requireAdmin(spaceId,userId);
        int queryCount = safe(queryLogMapper.countBySpaceId(spaceId));
        int citedCount = safe(queryLogMapper.countCitedBySpaceId(spaceId));
        SpaceRagAnalyticsSummaryVO vo = new SpaceRagAnalyticsSummaryVO();
        vo.setSpaceId(spaceId);
        vo.setQueryCount(queryCount);
        vo.setSuccessCount(safe(queryLogMapper.countSuccessBySpaceId(spaceId)));
        vo.setFailedCount(safe(queryLogMapper.countFailedBySpaceId(spaceId)));
        vo.setNoAnswerCount(safe(queryLogMapper.countNoAnswerBySpaceId(spaceId)));
        vo.setCitedQueryCount(citedCount);
        vo.setCitationCoverage(queryCount == 0 ? 0.0 : citedCount * 1.0 / queryCount);
        return vo;
    }

    public List<SpaceRagQueryLogVO> recentQueries(Long spaceId, Long userId, Integer limit) {
        spacePermissionService.requireAdmin(spaceId,userId);
        return toQueryVOs(queryLogMapper.listRecent(spaceId,safeLimit(limit)));
    }

    public List<SpaceRagQueryLogVO> noAnswerQueries(Long spaceId, Long userId, Integer limit) {
        spacePermissionService.requireAdmin(spaceId,userId);
        return toQueryVOs(queryLogMapper.listNoAnswer(spaceId,safeLimit(limit)));
    }

    public List<SpaceRagConfigLogVO> configLogs(Long spaceId, Long userId, Integer limit) {
        spacePermissionService.requireAdmin(spaceId,userId);
        List<SpaceRagConfigLogVO> result = new ArrayList<>();
        for(SpaceRagConfigLog log : configLogMapper.listRecent(spaceId,safeLimit(limit))) {
            SpaceRagConfigLogVO vo = new SpaceRagConfigLogVO();
            vo.setId(log.getId());
            vo.setSpaceId(log.getSpaceId());
            vo.setOperatorId(log.getOperatorId());
            vo.setChangedFields(log.getChangedFields());
            vo.setBeforeJson(log.getBeforeJson());
            vo.setAfterJson(log.getAfterJson());
            vo.setCreatetime(log.getCreatetime());
            result.add(vo);
        }
        return result;
    }

    private List<SpaceRagQueryLogVO> toQueryVOs(List<SpaceRagQueryLog> logs) {
        List<SpaceRagQueryLogVO> result = new ArrayList<>();
        for(SpaceRagQueryLog log : logs) {
            SpaceRagQueryLogVO vo = new SpaceRagQueryLogVO();
            vo.setId(log.getId());
            vo.setSpaceId(log.getSpaceId());
            vo.setUserId(log.getUserId());
            vo.setQuestion(log.getQuestion());
            vo.setAnswer(log.getAnswer());
            vo.setHitChunkIds(log.getHitChunkIds());
            vo.setModelName(log.getModelName());
            vo.setTopK(log.getTopK());
            vo.setTemperature(log.getTemperature());
            vo.setPromptTokens(log.getPromptTokens());
            vo.setCompletionTokens(log.getCompletionTokens());
            vo.setTotalTokens(log.getTotalTokens());
            vo.setSuccess(log.getSuccess());
            vo.setErrorMessage(log.getErrorMessage());
            vo.setCitationCount(countCitations(log.getHitChunkIds()));
            vo.setCreatetime(log.getCreatetime());
            result.add(vo);
        }
        return result;
    }

    private int countCitations(String hitChunkIds) {
        if(hitChunkIds == null || hitChunkIds.isBlank()) {
            return 0;
        }
        return hitChunkIds.split(",").length;
    }

    private int safe(Integer value) {
        return value == null ? 0 : value;
    }

    private int safeLimit(Integer limit) {
        if(limit == null || limit <= 0) {
            return 50;
        }
        return Math.min(limit,200);
    }
}
