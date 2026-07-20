package com.ylcloud.service.memory;

import com.ylcloud.DTO.UserMemorySettingUpdateDTO;
import com.ylcloud.DTO.UserMemoryUpdateDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.VO.UserMemorySettingVO;
import com.ylcloud.VO.UserMemoryStatsVO;
import com.ylcloud.VO.UserMemoryVO;
import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.UserMemoryItem;
import com.ylcloud.mapper.KnowledgeChatFeedbackMapper;
import com.ylcloud.mapper.KnowledgeChatMessageMapper;
import com.ylcloud.mapper.UserMemoryItemMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class UserMemoryManagementService {
    private final UserMemoryItemMapper mapper;
    private final UserMemoryService memoryService;
    private final RagProperties properties;
    private final KnowledgeChatMessageMapper messageMapper;
    private final KnowledgeChatFeedbackMapper feedbackMapper;

    public UserMemoryManagementService(UserMemoryItemMapper mapper, UserMemoryService memoryService, RagProperties properties,
                                       KnowledgeChatMessageMapper messageMapper, KnowledgeChatFeedbackMapper feedbackMapper) {
        this.mapper=mapper; this.memoryService=memoryService; this.properties=properties;
        this.messageMapper=messageMapper; this.feedbackMapper=feedbackMapper;
    }

    public List<UserMemoryVO> list(Long userId,String type,String keyword,Integer limit) {
        int safeLimit=limit==null||limit<=0?100:Math.min(limit,500);
        return mapper.listManaged(userId,type,keyword,safeLimit).stream().map(this::toVO).toList();
    }

    public UserMemorySettingVO setting(Long userId) {
        int fallback=properties.getMemory().getRetentionDays()==null?365:properties.getMemory().getRetentionDays();
        return new UserMemorySettingVO(memoryService.enabled(userId),mapper.retentionDays(userId,fallback));
    }

    @Transactional
    public UserMemorySettingVO updateSetting(Long userId,UserMemorySettingUpdateDTO dto) {
        boolean enabled=dto.getEnabled()==null||dto.getEnabled();
        int retention=setting(userId).getRetentionDays();
        mapper.saveSetting(userId,enabled,retention,LocalDateTime.now());
        if(Boolean.TRUE.equals(dto.getClearExisting())) memoryService.clear(userId);
        return new UserMemorySettingVO(enabled,retention);
    }

    @Transactional
    public UserMemoryVO update(Long userId,Long id,UserMemoryUpdateDTO dto) {
        UserMemoryItem current=requireOwned(userId,id);
        UserMemoryItem replacement=memoryService.acceptManual(userId,current.getSourceSessionId(),current.getSourceMessageId(),
                "manual-edit:"+id+":"+dto.getContent(),new UserMemoryCandidate(dto.getMemoryType(),current.getNormalizedKey(),dto.getContent(),1,true));
        if(replacement==null) throw new BaseException("记忆更新失败");
        memoryService.processIndex(replacement.getId());
        if(Boolean.TRUE.equals(current.getPinned())) mapper.setPinned(replacement.getId(),userId,true,LocalDateTime.now());
        return toVO(mapper.getOwned(replacement.getId(),userId));
    }

    public UserMemoryVO pin(Long userId,Long id,boolean pinned) {
        requireOwned(userId,id);
        if(mapper.setPinned(id,userId,pinned,LocalDateTime.now())==0) throw new BaseException("只能固定已激活的记忆");
        return toVO(mapper.getOwned(id,userId));
    }

    public void forget(Long userId,Long id) { requireOwned(userId,id); memoryService.forget(userId,id); }
    public void clear(Long userId) { memoryService.clear(userId); }

    public UserMemoryStatsVO stats(Long userId) {
        UserMemoryStatsVO vo=new UserMemoryStatsVO();
        vo.setActiveCount(mapper.countActive(userId)); vo.setPinnedCount(mapper.countPinned(userId));
        vo.setPendingCount(mapper.countPending(userId)); vo.setFailedCount(mapper.countFailed(userId));
        vo.setContextTokens(messageMapper.sumContextTokens(userId)); vo.setFeedbackCount(feedbackMapper.count(userId));
        vo.setHelpfulCount(feedbackMapper.helpful(userId)); return vo;
    }

    private UserMemoryItem requireOwned(Long userId,Long id) { UserMemoryItem item=mapper.getOwned(id,userId); if(item==null) throw new BaseException("记忆不存在"); return item; }
    private UserMemoryVO toVO(UserMemoryItem item) { UserMemoryVO vo=new UserMemoryVO(); vo.setId(item.getId()); vo.setSourceSessionId(item.getSourceSessionId()); vo.setSourceMessageId(item.getSourceMessageId()); vo.setMemoryType(item.getMemoryType()); vo.setContent(item.getContent()); vo.setNormalizedKey(item.getNormalizedKey()); vo.setConfidence(item.getConfidence()); vo.setUserConfirmed(item.getUserConfirmed()); vo.setPinned(item.getPinned()); vo.setExpiresAt(item.getExpiresAt()); vo.setVersion(item.getVersion()); vo.setMemoryStatus(item.getMemoryStatus()); vo.setCreatetime(item.getCreatetime()); vo.setUpdatetime(item.getUpdatetime()); return vo; }
}
