package com.ylcloud.service;

import com.ylcloud.DTO.KnowledgeChatMessageCreateDTO;
import com.ylcloud.DTO.KnowledgeChatSessionCreateDTO;
import com.ylcloud.DTO.KnowledgeChatSessionScopeUpdateDTO;
import com.ylcloud.DTO.KnowledgeChatSessionUpdateDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.VO.KnowledgeChatMessageVO;
import com.ylcloud.VO.KnowledgeChatSessionVO;
import com.ylcloud.constant.StatusConstant;
import com.ylcloud.entity.KnowledgeChatMessage;
import com.ylcloud.entity.KnowledgeChatSession;
import com.ylcloud.mapper.KnowledgeChatMessageMapper;
import com.ylcloud.mapper.KnowledgeChatSessionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class KnowledgeChatSessionService {
    private static final int DEFAULT_LIMIT = 50;

    private final KnowledgeChatSessionMapper sessionMapper;
    private final KnowledgeChatMessageMapper messageMapper;
    private final SpacePermissionService spacePermissionService;

    public KnowledgeChatSessionService(KnowledgeChatSessionMapper sessionMapper,
                                       KnowledgeChatMessageMapper messageMapper,
                                       SpacePermissionService spacePermissionService) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.spacePermissionService = spacePermissionService;
    }

    public List<KnowledgeChatSessionVO> list(Long userId, String keyword, Integer limit) {
        List<KnowledgeChatSessionVO> result = new ArrayList<>();
        for(KnowledgeChatSession session : sessionMapper.listByUser(userId,keyword,safeLimit(limit))) {
            KnowledgeChatSessionVO vo = toSessionVO(session,false);
            vo.setMessageCount(messageMapper.countBySessionId(session.getId()));
            result.add(vo);
        }
        return result;
    }

    @Transactional
    public KnowledgeChatSessionVO create(Long userId, KnowledgeChatSessionCreateDTO dto) {
        List<Long> spaceIds = normalizeSpaceIds(dto.getSpaceIds());
        requireSpaces(userId,spaceIds);
        LocalDateTime now = LocalDateTime.now();
        KnowledgeChatSession session = new KnowledgeChatSession();
        session.setUserId(userId);
        session.setTitle(resolveTitle(dto.getTitle()));
        session.setScopeMode(resolveScopeMode(dto.getScopeMode(),spaceIds));
        session.setScopeSpaceIds(joinSpaceIds(spaceIds));
        session.setStatus(StatusConstant.ENABLE);
        session.setCreatetime(now);
        session.setUpdatetime(now);
        sessionMapper.insert(session);
        return toSessionVO(session,true);
    }

    public KnowledgeChatSessionVO detail(Long userId, Long sessionId) {
        KnowledgeChatSession session = requireSession(userId,sessionId);
        return toSessionVO(session,true);
    }

    @Transactional
    public KnowledgeChatSessionVO updateTitle(Long userId, Long sessionId, KnowledgeChatSessionUpdateDTO dto) {
        requireSession(userId,sessionId);
        int rows = sessionMapper.updateTitle(sessionId,userId,dto.getTitle().trim(),LocalDateTime.now());
        if(rows == 0) {
            throw new BaseException("会话标题更新失败");
        }
        return detail(userId,sessionId);
    }

    @Transactional
    public KnowledgeChatSessionVO updateScope(Long userId, Long sessionId, KnowledgeChatSessionScopeUpdateDTO dto) {
        requireSession(userId,sessionId);
        List<Long> spaceIds = normalizeSpaceIds(dto.getSpaceIds());
        if(spaceIds.isEmpty()) {
            throw new BaseException("至少选择一个知识库");
        }
        requireSpaces(userId,spaceIds);
        int rows = sessionMapper.updateScope(sessionId,userId,resolveScopeMode(null,spaceIds),joinSpaceIds(spaceIds),LocalDateTime.now());
        if(rows == 0) {
            throw new BaseException("会话知识库范围更新失败");
        }
        return detail(userId,sessionId);
    }

    @Transactional
    public Boolean delete(Long userId, Long sessionId) {
        requireSession(userId,sessionId);
        messageMapper.disableBySessionId(sessionId);
        int rows = sessionMapper.disable(sessionId,userId,LocalDateTime.now());
        if(rows == 0) {
            throw new BaseException("会话删除失败");
        }
        return true;
    }

    @Transactional
    public KnowledgeChatMessageVO appendMessage(Long userId, Long sessionId, KnowledgeChatMessageCreateDTO dto) {
        requireSession(userId,sessionId);
        KnowledgeChatMessage message = new KnowledgeChatMessage();
        message.setSessionId(sessionId);
        message.setUserId(userId);
        message.setRole(dto.getRole() == null || dto.getRole().isBlank() ? "user" : dto.getRole());
        message.setContent(dto.getContent());
        message.setCitationsJson(dto.getCitationsJson());
        message.setStatus(StatusConstant.ENABLE);
        message.setCreatetime(LocalDateTime.now());
        messageMapper.insert(message);
        sessionMapper.touch(sessionId,userId,LocalDateTime.now());
        return toMessageVO(message);
    }

    private KnowledgeChatSession requireSession(Long userId, Long sessionId) {
        KnowledgeChatSession session = sessionMapper.getActive(sessionId,userId);
        if(session == null) {
            throw new BaseException("会话不存在");
        }
        return session;
    }

    private void requireSpaces(Long userId, List<Long> spaceIds) {
        for(Long spaceId : spaceIds) {
            spacePermissionService.requireMember(spaceId,userId);
        }
    }

    private KnowledgeChatSessionVO toSessionVO(KnowledgeChatSession session, boolean withMessages) {
        KnowledgeChatSessionVO vo = new KnowledgeChatSessionVO();
        vo.setId(session.getId());
        vo.setUserId(session.getUserId());
        vo.setTitle(session.getTitle());
        vo.setScopeMode(session.getScopeMode());
        vo.setSpaceIds(parseSpaceIds(session.getScopeSpaceIds()));
        vo.setCreatetime(session.getCreatetime());
        vo.setUpdatetime(session.getUpdatetime());
        if(withMessages) {
            List<KnowledgeChatMessageVO> messages = new ArrayList<>();
            for(KnowledgeChatMessage message : messageMapper.listBySessionId(session.getId())) {
                messages.add(toMessageVO(message));
            }
            vo.setMessages(messages);
            vo.setMessageCount(messages.size());
        }
        return vo;
    }

    private KnowledgeChatMessageVO toMessageVO(KnowledgeChatMessage message) {
        KnowledgeChatMessageVO vo = new KnowledgeChatMessageVO();
        vo.setId(message.getId());
        vo.setSessionId(message.getSessionId());
        vo.setRole(message.getRole());
        vo.setContent(message.getContent());
        vo.setCitationsJson(message.getCitationsJson());
        vo.setCreatetime(message.getCreatetime());
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

    private String resolveScopeMode(String scopeMode, List<Long> spaceIds) {
        if(scopeMode != null && !scopeMode.isBlank()) {
            return scopeMode;
        }
        return spaceIds.size() > 1 ? "multi-space" : "single-space";
    }

    private String resolveTitle(String title) {
        return title == null || title.isBlank() ? "新对话" : title.trim();
    }

    private String joinSpaceIds(List<Long> spaceIds) {
        StringBuilder builder = new StringBuilder();
        for(Long spaceId : spaceIds) {
            if(builder.length() > 0) {
                builder.append(",");
            }
            builder.append(spaceId);
        }
        return builder.toString();
    }

    private List<Long> parseSpaceIds(String raw) {
        List<Long> result = new ArrayList<>();
        if(raw == null || raw.isBlank()) {
            return result;
        }
        for(String item : raw.split(",")) {
            try {
                result.add(Long.parseLong(item.trim()));
            } catch(NumberFormatException ignored) {
                // ignore invalid stored scope item
            }
        }
        return result;
    }

    private int safeLimit(Integer limit) {
        if(limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit,200);
    }
}
