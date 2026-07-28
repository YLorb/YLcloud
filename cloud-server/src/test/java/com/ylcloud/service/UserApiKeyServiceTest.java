package com.ylcloud.service;

import com.ylcloud.DTO.UserApiKeyCreateDTO;
import com.ylcloud.DTO.UserFileDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.VO.SpaceVO;
import com.ylcloud.entity.User;
import com.ylcloud.entity.UserApiKey;
import com.ylcloud.mapper.AgentRiskAuthorizationMapper;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.LoginMapper;
import com.ylcloud.mapper.SpaceMapper;
import com.ylcloud.mapper.UserApiKeyMapper;
import com.ylcloud.security.ApiKeyPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserApiKeyServiceTest {
    @Test
    void createsOneTimePlaintextWithBcryptHashAndVisibleSpaceSnapshot() {
        Fixture fixture = fixture();
        AtomicReference<UserApiKey> inserted = new AtomicReference<>();
        Set<String> storedScopes = new LinkedHashSet<>();
        Set<Long> storedSpaces = new LinkedHashSet<>();
        when(fixture.keys.insert(any())).thenAnswer(call -> {
            UserApiKey key = call.getArgument(0);
            key.setId(31L);
            inserted.set(key);
            return 1;
        });
        when(fixture.keys.insertScope(eq(31L),anyString(),any())).thenAnswer(call -> { storedScopes.add(call.getArgument(1)); return 1; });
        when(fixture.keys.insertSpaceScope(eq(31L),anyLong(),any())).thenAnswer(call -> { storedSpaces.add(call.getArgument(1)); return 1; });
        UserApiKeyCreateDTO dto = validDto();

        var created = fixture.service.create(7L,dto);

        assertTrue(created.getPlaintext().matches("ylk_[A-Za-z0-9_-]{12}_[A-Za-z0-9_-]{43}"));
        assertFalse(inserted.get().getKeyHash().contains(created.getPlaintext()));
        assertTrue(new BCryptPasswordEncoder().matches(created.getPlaintext(),inserted.get().getKeyHash()));
        assertEquals(Set.of(5L,6L),storedSpaces);
        assertEquals(Set.of("DRIVE_READ","DRIVE_WRITE","KNOWLEDGE_RETRIEVE","KNOWLEDGE_AGENT"),storedScopes);
        verify(fixture.risk).issueForApiKey(7L,31L,"PERSISTENT",null,true,7L);
    }

    @Test
    void authenticatesByPrefixAndHashThenRejectsWrongExpiredOrRevokedKey() {
        Fixture fixture = fixture();
        String plaintext = "ylk_abcdefghijkl_" + "x".repeat(43);
        UserApiKey key = key(31L,"ylk_abcdefghijkl",new BCryptPasswordEncoder().encode(plaintext));
        when(fixture.keys.getByPrefix("ylk_abcdefghijkl")).thenReturn(key);
        when(fixture.keys.touch(eq(31L),any())).thenReturn(1);
        when(fixture.keys.listScopes(31L)).thenReturn(Set.of("DRIVE_READ"));
        when(fixture.keys.listSpaceIds(31L)).thenReturn(Set.of());

        ApiKeyPrincipal principal = fixture.service.authenticate(plaintext);

        assertEquals(31L,principal.keyId());
        assertThrows(BaseException.class,() -> fixture.service.authenticate(
                "ylk_abcdefghijkl_" + "y".repeat(43)));
        key.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        assertThrows(BaseException.class,() -> fixture.service.authenticate(plaintext));
        key.setExpiresAt(null);
        key.setKeyStatus("REVOKED");
        assertThrows(BaseException.class,() -> fixture.service.authenticate(plaintext));
    }

    @Test
    void enforcesCountExpiryAndNeverExpireSitePolicies() {
        Fixture fixture = fixture();
        when(fixture.keys.countActiveByUser(7L)).thenReturn(10);
        assertThrows(BaseException.class,() -> fixture.service.create(7L,validDto()));
        when(fixture.keys.countActiveByUser(7L)).thenReturn(0);
        when(fixture.settings.getBoolean("apiKey.allowNeverExpires",true)).thenReturn(false);
        assertThrows(BaseException.class,() -> fixture.service.create(7L,validDto()));
        UserApiKeyCreateDTO expiring = validDto();
        expiring.setNeverExpires(false);
        expiring.setExpiresAt(LocalDateTime.now().plusDays(366));
        assertThrows(BaseException.class,() -> fixture.service.create(7L,expiring));
    }

    @Test
    void directoryMoveRequiresSourceAndTargetInsideImmutableRootBoundary() {
        Fixture fixture = fixture();
        ApiKeyPrincipal principal = new ApiKeyPrincipal(31L,7L,"prefix","WRITE",10L,
                Set.of("DRIVE_READ","DRIVE_WRITE"),Set.of());
        when(fixture.keys.countWithinDriveRoot(7L,11L,10L)).thenReturn(1);
        when(fixture.keys.countWithinDriveRoot(7L,12L,10L)).thenReturn(0);

        assertThrows(BaseException.class,() -> fixture.service.requireDriveMove(principal,11L,12L));

        verify(fixture.keys).countWithinDriveRoot(7L,11L,10L);
        verify(fixture.keys).countWithinDriveRoot(7L,12L,10L);
    }

    @Test
    void spaceSnapshotStillRequiresCurrentMembershipAndHighRiskIsKeyBound() {
        Fixture fixture = fixture();
        ApiKeyPrincipal principal = new ApiKeyPrincipal(31L,7L,"prefix","NONE",null,
                Set.of("KNOWLEDGE_RETRIEVE","KNOWLEDGE_AGENT"),Set.of(5L));
        when(fixture.keys.countSpaceScope(31L,5L)).thenReturn(1);
        doThrow(new BaseException(403,"membership revoked")).when(fixture.spacePermission).requireMember(5L,7L);

        assertThrows(BaseException.class,() -> fixture.service.requireSpace(principal,5L,false));
        fixture.service.requireHighRiskAgent(principal,"invoke-1");

        verify(fixture.risk).authorizeHighRisk(7L,31L,"invoke-1");
    }

    @Test
    void revokeIsOwnerBoundAndAlsoRevokesKeyRiskCapability() {
        Fixture fixture = fixture();
        UserApiKey key = key(31L,"prefix","hash");
        when(fixture.keys.lockById(31L)).thenReturn(key);
        when(fixture.keys.revoke(eq(31L),any())).thenReturn(1);

        fixture.service.revoke(7L,31L);

        verify(fixture.risk).replaceForApiKey(7L,31L,false,null,false);
        assertThrows(BaseException.class,() -> fixture.service.revoke(8L,31L));
    }

    private Fixture fixture() {
        UserApiKeyMapper keys = mock(UserApiKeyMapper.class);
        LoginMapper login = mock(LoginMapper.class);
        FileInfoMapper files = mock(FileInfoMapper.class);
        SpaceMapper spaces = mock(SpaceMapper.class);
        SpacePermissionService permission = mock(SpacePermissionService.class);
        SiteSettingService settings = mock(SiteSettingService.class);
        AgentRiskAuthorizationService risk = mock(AgentRiskAuthorizationService.class);
        AgentRiskAuthorizationMapper riskMapper = mock(AgentRiskAuthorizationMapper.class);
        User user = new User(); user.setId(7L); user.setStatus(1);
        when(login.getById(7L)).thenReturn(user);
        when(settings.getBoolean("apiKey.enabled",true)).thenReturn(true);
        when(settings.getBoolean("apiKey.allowNeverExpires",true)).thenReturn(true);
        when(settings.getLong("apiKey.maxPerUser",10L)).thenReturn(10L);
        when(settings.getLong("apiKey.maxValidityDays",365L)).thenReturn(365L);
        UserFileDTO root = UserFileDTO.builder().id(10L).userId(7L).Dir(1).status(1).build();
        when(files.getByFileId(10L,7L)).thenReturn(root);
        SpaceVO first = new SpaceVO(); first.setId(5L);
        SpaceVO second = new SpaceVO(); second.setId(6L);
        when(spaces.listByUserId(7L)).thenReturn(List.of(first,second));
        return new Fixture(keys,login,files,spaces,permission,settings,risk,riskMapper,
                new UserApiKeyService(keys,login,files,spaces,permission,settings,risk,riskMapper));
    }

    private UserApiKeyCreateDTO validDto() {
        UserApiKeyCreateDTO dto = new UserApiKeyCreateDTO();
        dto.setName("automation");
        dto.setDriveAccess("WRITE");
        dto.setDriveRootFileId(10L);
        dto.setKnowledgeRetrieve(true);
        dto.setKnowledgeAgent(true);
        dto.setSelectAllVisibleSpaces(true);
        dto.setNeverExpires(true);
        dto.setAllowHighRisk(true);
        dto.setRiskAcknowledged(true);
        return dto;
    }

    private UserApiKey key(Long id,String prefix,String hash) {
        UserApiKey key = new UserApiKey();
        key.setId(id); key.setUserId(7L); key.setKeyName("test"); key.setKeyPrefix(prefix); key.setKeyHash(hash);
        key.setDriveAccess("READ"); key.setDriveRootFileId(10L); key.setKeyStatus("ACTIVE");
        key.setCreateTime(LocalDateTime.now()); key.setUpdateTime(LocalDateTime.now());
        return key;
    }

    private record Fixture(UserApiKeyMapper keys,LoginMapper login,FileInfoMapper files,SpaceMapper spaces,
                           SpacePermissionService spacePermission,SiteSettingService settings,
                           AgentRiskAuthorizationService risk,AgentRiskAuthorizationMapper riskMapper,
                           UserApiKeyService service) {
    }
}
