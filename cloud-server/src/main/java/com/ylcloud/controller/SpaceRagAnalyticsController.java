package com.ylcloud.controller;

import com.ylcloud.Result;
import com.ylcloud.VO.SpaceRagAnalyticsSummaryVO;
import com.ylcloud.VO.SpaceRagConfigLogVO;
import com.ylcloud.VO.SpaceRagQueryLogVO;
import com.ylcloud.context.BaseContext;
import com.ylcloud.service.SpaceRagAnalyticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/space/{spaceId}/rag/analytics")
public class SpaceRagAnalyticsController {
    private final SpaceRagAnalyticsService analyticsService;

    public SpaceRagAnalyticsController(SpaceRagAnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/summary")
    public Result<SpaceRagAnalyticsSummaryVO> summary(@PathVariable Long spaceId) {
        return Result.success(analyticsService.summary(spaceId,BaseContext.getCurrentId()));
    }

    @GetMapping("/queries")
    public Result<List<SpaceRagQueryLogVO>> queries(@PathVariable Long spaceId,
                                                    @RequestParam(required = false) Integer limit) {
        return Result.success(analyticsService.recentQueries(spaceId,BaseContext.getCurrentId(),limit));
    }

    @GetMapping("/no-answer")
    public Result<List<SpaceRagQueryLogVO>> noAnswer(@PathVariable Long spaceId,
                                                     @RequestParam(required = false) Integer limit) {
        return Result.success(analyticsService.noAnswerQueries(spaceId,BaseContext.getCurrentId(),limit));
    }

    @GetMapping("/config-logs")
    public Result<List<SpaceRagConfigLogVO>> configLogs(@PathVariable Long spaceId,
                                                        @RequestParam(required = false) Integer limit) {
        return Result.success(analyticsService.configLogs(spaceId,BaseContext.getCurrentId(),limit));
    }
}
