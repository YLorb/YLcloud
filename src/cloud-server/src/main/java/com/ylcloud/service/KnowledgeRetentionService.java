package com.ylcloud.service;

import com.ylcloud.Exception.BaseException;
import com.ylcloud.VO.KnowledgeRetentionImpactVO;
import com.ylcloud.entity.KnowledgeRevision;
import com.ylcloud.entity.SpaceRagConfig;
import com.ylcloud.mapper.KnowledgeRevisionMapper;
import com.ylcloud.mapper.SpaceRagMapper;
import com.ylcloud.service.space.SpacePermissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Read-only impact preview; it neither changes rights nor schedules physical deletion. */
@Service
public class KnowledgeRetentionService {
    private final SpacePermissionService permissions;
    private final SpaceRagMapper configs;
    private final KnowledgeRevisionMapper revisions;
    private final KnowledgeRevisionService revisionService;

    public KnowledgeRetentionService(SpacePermissionService permissions, SpaceRagMapper configs,
                                     KnowledgeRevisionMapper revisions, KnowledgeRevisionService revisionService) {
        this.permissions=permissions; this.configs=configs; this.revisions=revisions; this.revisionService=revisionService;
    }

    @Transactional(readOnly=true)
    public KnowledgeRetentionImpactVO preview(Long spaceId, Long userId, boolean historicalEvidenceEnabled, Integer retentionDays) {
        permissions.requireAdmin(spaceId,userId);
        if(retentionDays!=null && retentionDays<=0) throw new BaseException("历史版本保留天数必须大于 0");
        if(historicalEvidenceEnabled && retentionDays==null) throw new BaseException("开启历史版本复核前必须设置保留天数");
        SpaceRagConfig config=configs.getBySpaceId(spaceId);
        if(config==null) throw new BaseException("知识库配置不存在");
        KnowledgeRetentionImpactVO result=new KnowledgeRetentionImpactVO();
        result.setConfigRevision(config.getConfigRevision()==null ? 0L : config.getConfigRevision());
        if(!Boolean.TRUE.equals(config.getHistoricalEvidenceEnabled())) return result;
        LocalDateTime now=LocalDateTime.now();
        List<KnowledgeRevision> all=revisions.listBySpace(spaceId);
        Map<Long,Long> latest=new HashMap<>();
        for(KnowledgeRevision revision:all) latest.merge(revision.getSpaceFileId(),revision.getId(),Math::max);
        for(KnowledgeRevision revision:all) {
            if(revision.getSupersededAt()==null) continue;
            boolean transition=Boolean.TRUE.equals(config.getAllowStaleDuringReindex())
                    && Objects.equals(latest.get(revision.getSpaceFileId()),revision.getId())
                    && revisions.current(spaceId,revision.getSpaceFileId())==null;
            if(transition || revisionService.safeRevisionById(spaceId,revision.getSpaceFileId(),revision.getId(),false)==null) continue;
            result.setCurrentlyAccessible(result.getCurrentlyAccessible()+1);
            if(!historicalEvidenceEnabled || !revision.getSupersededAt().plusDays(retentionDays).isAfter(now)) {
                result.setAffectedRevisions(result.getAffectedRevisions()+1);
            }
        }
        return result;
    }
}
