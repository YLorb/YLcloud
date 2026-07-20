package com.ylcloud.service;

import com.ylcloud.DTO.KnowledgeChatFeedbackDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.VO.KnowledgeChatEpisodeVO;
import com.ylcloud.entity.KnowledgeChatMessage;
import com.ylcloud.entity.KnowledgeChatSession;
import com.ylcloud.mapper.KnowledgeChatEpisodeMapper;
import com.ylcloud.mapper.KnowledgeChatFeedbackMapper;
import com.ylcloud.mapper.KnowledgeChatMessageMapper;
import com.ylcloud.mapper.KnowledgeChatSessionMapper;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class KnowledgeChatEpisodeService {
    private static final int EPISODE_SIZE=20;
    private final KnowledgeChatEpisodeMapper episodeMapper; private final KnowledgeChatSessionMapper sessionMapper;
    private final KnowledgeChatMessageMapper messageMapper; private final KnowledgeChatFeedbackMapper feedbackMapper;
    public KnowledgeChatEpisodeService(KnowledgeChatEpisodeMapper episodeMapper,KnowledgeChatSessionMapper sessionMapper,
                                       KnowledgeChatMessageMapper messageMapper,KnowledgeChatFeedbackMapper feedbackMapper) {
        this.episodeMapper=episodeMapper; this.sessionMapper=sessionMapper; this.messageMapper=messageMapper; this.feedbackMapper=feedbackMapper;
    }
    public List<KnowledgeChatEpisodeVO> list(Long userId,Long sessionId) {
        KnowledgeChatSession session=sessionMapper.getActive(sessionId,userId); if(session==null) throw new BaseException("会话不存在");
        List<KnowledgeChatMessage> valid=messageMapper.listBySessionId(sessionId).stream().filter(m -> "user".equals(m.getRole())||"SUCCESS".equals(m.getTaskStatus())).toList();
        LocalDateTime now=LocalDateTime.now();
        for(int start=0,no=1;start<valid.size();start+=EPISODE_SIZE,no++) {
            List<KnowledgeChatMessage> part=valid.subList(start,Math.min(start+EPISODE_SIZE,valid.size()));
            KnowledgeChatMessage firstUser=part.stream().filter(m -> "user".equals(m.getRole())).findFirst().orElse(part.get(0));
            String title=truncate(firstUser.getContent(),80); String summary=summary(part);
            episodeMapper.upsert(sessionId,userId,no,part.get(0).getSequenceNo(),part.get(part.size()-1).getSequenceNo(),title,summary,part.size(),now);
        }
        return episodeMapper.list(sessionId,userId);
    }
    public void feedback(Long userId,Long sessionId,Long messageId,KnowledgeChatFeedbackDTO dto) {
        KnowledgeChatMessage message=messageMapper.getOwned(messageId,sessionId,userId);
        if(message==null||!"assistant".equals(message.getRole())||!"SUCCESS".equals(message.getTaskStatus())) throw new BaseException("只能评价已完成的回答");
        feedbackMapper.save(userId,sessionId,messageId,dto.getRating(),dto.getReason(),dto.getComment(),LocalDateTime.now());
    }
    public void delete(Long userId,Long sessionId) { episodeMapper.disable(sessionId,userId,LocalDateTime.now()); }
    private String summary(List<KnowledgeChatMessage> messages) { List<String> parts=new ArrayList<>(); for(KnowledgeChatMessage m:messages) if("user".equals(m.getRole())) parts.add(truncate(m.getContent(),120)); return truncate(String.join("；",parts),1000); }
    private String truncate(String value,int limit) { if(value==null)return ""; return value.substring(0,Math.min(limit,value.length())); }
}
