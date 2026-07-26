package com.ylcloud.service;

import com.ylcloud.DTO.UserApiKeyCreateDTO;
import com.ylcloud.DTO.UserFileDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.VO.SpaceVO;
import com.ylcloud.VO.UserApiKeyCreatedVO;
import com.ylcloud.VO.UserApiKeyVO;
import com.ylcloud.entity.User;
import com.ylcloud.entity.UserApiKey;
import com.ylcloud.mapper.AgentRiskAuthorizationMapper;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.LoginMapper;
import com.ylcloud.mapper.SpaceMapper;
import com.ylcloud.mapper.UserApiKeyMapper;
import com.ylcloud.security.ApiKeyPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class UserApiKeyService {
    public static final String DRIVE_READ = "DRIVE_READ";
    public static final String DRIVE_WRITE = "DRIVE_WRITE";
    public static final String KNOWLEDGE_RETRIEVE = "KNOWLEDGE_RETRIEVE";
    public static final String KNOWLEDGE_AGENT = "KNOWLEDGE_AGENT";
    private static final Pattern KEY_PATTERN = Pattern.compile("^(ylk_[A-Za-z0-9_-]{12})_([A-Za-z0-9_-]{43})$");
    private static final String DUMMY_HASH = "$2a$10$tYJdXzBP4YlqdKqZzhy2n.T4HvuvfJ4hEX9gVBxGOvALvXlqTj1uK";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final UserApiKeyMapper mapper;
    private final LoginMapper loginMapper;
    private final FileInfoMapper fileInfoMapper;
    private final SpaceMapper spaceMapper;
    private final SpacePermissionService spacePermissionService;
    private final SiteSettingService siteSettingService;
    private final AgentRiskAuthorizationService riskAuthorizationService;
    private final AgentRiskAuthorizationMapper riskAuthorizationMapper;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Transactional
    public UserApiKeyCreatedVO create(Long userId,UserApiKeyCreateDTO dto) {
        requireApiKeysEnabled();
        requireAccountActive(userId);
        int maximum = siteSettingService.getLong("apiKey.maxPerUser",10L).intValue();
        if(maximum > 0 && mapper.countActiveByUser(userId) >= maximum) {
            throw new BaseException("API Key 数量已达到站点上限");
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = validateExpiry(dto,now);
        Set<String> scopes = scopes(dto);
        if(scopes.isEmpty()) throw new BaseException("至少启用一项 API Key 能力");
        Long driveRootId = validateDriveRoot(userId,dto);
        Set<Long> spaces = snapshotSpaces(userId,dto,scopes);
        if(dto.isAllowHighRisk() && !dto.isKnowledgeAgent()) {
            throw new BaseException("高风险能力只能与知识 Agent Scope 一起启用");
        }
        if(dto.isAllowHighRisk() && !dto.isRiskAcknowledged()) {
            throw new BaseException("必须明确接受 API Key 高风险 Agent 能力风险");
        }
        String prefix = uniquePrefix();
        String plaintext = prefix + "_" + randomUrlToken(32);
        UserApiKey value = new UserApiKey();
        value.setUserId(userId);
        value.setKeyName(dto.getName().trim());
        value.setKeyPrefix(prefix);
        value.setKeyHash(passwordEncoder.encode(plaintext));
        value.setDriveAccess(dto.getDriveAccess());
        value.setDriveRootFileId(driveRootId);
        value.setKeyStatus("ACTIVE");
        value.setExpiresAt(expiresAt);
        value.setCreateTime(now);
        value.setUpdateTime(now);
        mapper.insert(value);
        scopes.forEach(scope -> mapper.insertScope(value.getId(),scope,now));
        spaces.forEach(spaceId -> mapper.insertSpaceScope(value.getId(),spaceId,now));
        if(dto.isAllowHighRisk()) {
            riskAuthorizationService.issueForApiKey(userId,value.getId(),"PERSISTENT",expiresAt,true,userId);
        }
        UserApiKeyCreatedVO result = new UserApiKeyCreatedVO();
        result.setApiKey(toVO(value,scopes,spaces,dto.isAllowHighRisk()));
        result.setPlaintext(plaintext);
        return result;
    }

    public List<UserApiKeyVO> list(Long userId) {
        return mapper.listByUser(userId).stream().map(key -> toVO(key,mapper.listScopes(key.getId()),
                mapper.listSpaceIds(key.getId()),riskAuthorizationMapper.countActiveForApiKey(userId,key.getId()) > 0)).toList();
    }

    @Transactional
    public void revoke(Long userId,Long keyId) {
        UserApiKey key = mapper.lockById(keyId);
        if(key == null || !userId.equals(key.getUserId()) || !"ACTIVE".equals(key.getKeyStatus())) {
            throw new BaseException("API Key 不存在或已撤销");
        }
        if(mapper.revoke(keyId,LocalDateTime.now()) != 1) throw new BaseException("API Key 撤销失败");
        riskAuthorizationService.replaceForApiKey(userId,keyId,false,key.getExpiresAt(),false);
    }

    @Transactional
    public ApiKeyPrincipal authenticate(String plaintext) {
        requireApiKeysEnabled();
        Matcher matcher = plaintext == null ? KEY_PATTERN.matcher("") : KEY_PATTERN.matcher(plaintext);
        String prefix = matcher.matches() ? matcher.group(1) : null;
        UserApiKey key = prefix == null ? null : mapper.getByPrefix(prefix);
        String expectedHash = key == null ? DUMMY_HASH : key.getKeyHash();
        boolean hashMatches = plaintext != null && passwordEncoder.matches(plaintext,expectedHash);
        LocalDateTime now = LocalDateTime.now();
        if(key == null || !hashMatches || !"ACTIVE".equals(key.getKeyStatus())
                || (key.getExpiresAt() != null && !key.getExpiresAt().isAfter(now))) {
            throw new BaseException(401,"API Key 无效、已过期或已撤销");
        }
        requireAccountActive(key.getUserId());
        if(mapper.touch(key.getId(),now) != 1) throw new BaseException(401,"API Key 已失效");
        return new ApiKeyPrincipal(key.getId(),key.getUserId(),key.getKeyPrefix(),key.getDriveAccess(),
                key.getDriveRootFileId(),Set.copyOf(mapper.listScopes(key.getId())),Set.copyOf(mapper.listSpaceIds(key.getId())));
    }

    public void requireDriveResource(ApiKeyPrincipal principal,Long fileId,boolean write) {
        requireApiKeysEnabled();
        requireAccountActive(principal.userId());
        String required = write ? DRIVE_WRITE : DRIVE_READ;
        if(!principal.scopes().contains(required) || principal.driveRootFileId() == null
                || mapper.countWithinDriveRoot(principal.userId(),fileId,principal.driveRootFileId()) == 0) {
            throw new BaseException(403,"文件超出 API Key 授权目录或访问模式");
        }
    }

    public void requireDriveMove(ApiKeyPrincipal principal,Long sourceFileId,Long targetParentId) {
        requireDriveResource(principal,sourceFileId,true);
        requireDriveResource(principal,targetParentId,true);
    }

    public void requireSpace(ApiKeyPrincipal principal,Long spaceId,boolean agent) {
        requireApiKeysEnabled();
        requireAccountActive(principal.userId());
        String required = agent ? KNOWLEDGE_AGENT : KNOWLEDGE_RETRIEVE;
        if(!principal.scopes().contains(required) || !principal.spaceIds().contains(spaceId)
                || mapper.countSpaceScope(principal.keyId(),spaceId) == 0) {
            throw new BaseException(403,"Space 超出 API Key 授权范围");
        }
        spacePermissionService.requireMember(spaceId,principal.userId());
    }

    public void requireHighRiskAgent(ApiKeyPrincipal principal,String invocationId) {
        requireApiKeysEnabled();
        requireAccountActive(principal.userId());
        if(!principal.scopes().contains(KNOWLEDGE_AGENT)) throw new BaseException(403,"API Key 未启用知识 Agent Scope");
        riskAuthorizationService.authorizeHighRisk(principal.userId(),principal.keyId(),invocationId);
    }

    private LocalDateTime validateExpiry(UserApiKeyCreateDTO dto,LocalDateTime now) {
        if(dto.isNeverExpires() == (dto.getExpiresAt() != null)) {
            throw new BaseException("必须且只能选择到期时间或永不过期");
        }
        if(dto.isNeverExpires()) {
            if(!siteSettingService.getBoolean("apiKey.allowNeverExpires",true)) throw new BaseException("站点禁止永不过期 API Key");
            return null;
        }
        if(!dto.getExpiresAt().isAfter(now)) throw new BaseException("API Key 到期时间必须晚于当前时间");
        long maxDays = siteSettingService.getLong("apiKey.maxValidityDays",365L);
        if(maxDays > 0 && dto.getExpiresAt().isAfter(now.plusDays(maxDays))) throw new BaseException("API Key 有效期超过站点上限");
        return dto.getExpiresAt();
    }

    private Long validateDriveRoot(Long userId,UserApiKeyCreateDTO dto) {
        if("NONE".equals(dto.getDriveAccess())) {
            if(dto.getDriveRootFileId() != null) throw new BaseException("未启用云盘能力时不能设置根目录");
            return null;
        }
        UserFileDTO root = dto.getDriveRootFileId() == null ? null : fileInfoMapper.getByFileId(dto.getDriveRootFileId(),userId);
        if(root == null || root.getDir() != 1) throw new BaseException("API Key 云盘根目录无效");
        return root.getId();
    }

    private Set<Long> snapshotSpaces(Long userId,UserApiKeyCreateDTO dto,Set<String> scopes) {
        boolean knowledge = scopes.contains(KNOWLEDGE_RETRIEVE) || scopes.contains(KNOWLEDGE_AGENT);
        Set<Long> requested = dto.getSpaceIds() == null ? Set.of() : new LinkedHashSet<>(dto.getSpaceIds());
        Set<Long> visible = spaceMapper.listByUserId(userId).stream().map(SpaceVO::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if(!knowledge) {
            if(dto.isSelectAllVisibleSpaces() || !requested.isEmpty()) throw new BaseException("未启用知识能力时不能设置 Space Scope");
            return Set.of();
        }
        if(dto.isSelectAllVisibleSpaces()) return visible;
        if(requested.isEmpty()) throw new BaseException("知识 API Key 至少绑定一个 Space");
        if(!visible.containsAll(requested)) throw new BaseException("Space 不可见或无权授权");
        return requested;
    }

    private Set<String> scopes(UserApiKeyCreateDTO dto) {
        Set<String> result = new LinkedHashSet<>();
        if("READ".equals(dto.getDriveAccess())) result.add(DRIVE_READ);
        if("WRITE".equals(dto.getDriveAccess())) {
            result.add(DRIVE_READ);
            result.add(DRIVE_WRITE);
        }
        if(dto.isKnowledgeRetrieve()) result.add(KNOWLEDGE_RETRIEVE);
        if(dto.isKnowledgeAgent()) result.add(KNOWLEDGE_AGENT);
        return result;
    }

    private String uniquePrefix() {
        for(int attempt=0;attempt<5;attempt++) {
            String prefix = "ylk_" + randomUrlToken(9);
            if(mapper.getByPrefix(prefix) == null) return prefix;
        }
        throw new BaseException("无法生成唯一 API Key 前缀");
    }

    private String randomUrlToken(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private void requireApiKeysEnabled() {
        if(!siteSettingService.getBoolean("apiKey.enabled",true)) throw new BaseException(403,"站点已禁用 API Key");
    }

    private void requireAccountActive(Long userId) {
        User user = loginMapper.getById(userId);
        if(user == null || user.getStatus() == null || user.getStatus() != 1) throw new BaseException(403,"API Key 所属账号不可用");
    }

    private UserApiKeyVO toVO(UserApiKey key,Set<String> scopes,Set<Long> spaces,boolean highRisk) {
        UserApiKeyVO vo = new UserApiKeyVO();
        vo.setId(key.getId());
        vo.setName(key.getKeyName());
        vo.setPrefix(key.getKeyPrefix());
        vo.setDriveAccess(key.getDriveAccess());
        vo.setDriveRootFileId(key.getDriveRootFileId());
        vo.setScopes(scopes == null ? Set.of() : Set.copyOf(scopes));
        vo.setSpaceIds(spaces == null ? Set.of() : Set.copyOf(spaces));
        vo.setHighRiskEnabled(highRisk);
        vo.setStatus("ACTIVE".equals(key.getKeyStatus()) && key.getExpiresAt() != null
                && !key.getExpiresAt().isAfter(LocalDateTime.now()) ? "EXPIRED" : key.getKeyStatus());
        vo.setExpiresAt(key.getExpiresAt());
        vo.setLastUsedAt(key.getLastUsedAt());
        vo.setRevokedAt(key.getRevokedAt());
        vo.setCreateTime(key.getCreateTime());
        vo.setUpdateTime(key.getUpdateTime());
        return vo;
    }
}
