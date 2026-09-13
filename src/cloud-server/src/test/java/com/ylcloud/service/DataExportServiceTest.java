package com.ylcloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.VO.SpaceVO;
import com.ylcloud.async.task.UnifiedTaskCenterService;
import com.ylcloud.entity.SpaceFile;
import com.ylcloud.mapper.DataExportJobMapper;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.KnowledgeChatMessageMapper;
import com.ylcloud.mapper.KnowledgeChatSessionMapper;
import com.ylcloud.mapper.LoginMapper;
import com.ylcloud.mapper.SpaceFileMapper;
import com.ylcloud.mapper.SpaceMapper;
import com.ylcloud.mapper.UserMemoryItemMapper;
import com.ylcloud.utils.MinioclientUtil;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataExportServiceTest {
    @Test
    void missingMasterKeyFailsClosed() {
        Fixture fixture = new Fixture();
        ReflectionTestUtils.setField(fixture.service, "masterKeyFile", "");
        ReflectionTestUtils.setField(fixture.service, "masterKeyBase64", "");

        assertThrows(IllegalStateException.class, fixture.service::resolveMasterKey);
    }

    @Test
    void configuredMasterKeyIsStableWithinProcess() {
        Fixture fixture = new Fixture();
        ReflectionTestUtils.setField(fixture.service, "masterKeyFile", "");
        ReflectionTestUtils.setField(fixture.service, "masterKeyBase64",
                java.util.Base64.getEncoder().encodeToString(new byte[32]));

        SecretKey first = fixture.service.resolveMasterKey();
        SecretKey second = fixture.service.resolveMasterKey();

        assertNotNull(first);
        assertArrayEquals(first.getEncoded(), second.getEncoded());
    }

    @Test
    @SuppressWarnings("unchecked")
    void spaceExportIncludesPersonalContentButOnlyTeamMembershipMetadata() {
        Fixture fixture = new Fixture();
        SpaceVO personal = space(1L, "PERSONAL", "OWNER", 7L);
        SpaceVO team = space(2L, "TEAM", "MEMBER", 8L);
        SpaceFile personalFile = new SpaceFile();
        personalFile.setId(10L);
        personalFile.setFileUuid("personal-object");
        personalFile.setFileName("mine.txt");
        personalFile.setDir(0);
        when(fixture.spaceMapper.listByUserId(7L)).thenReturn(List.of(personal, team));
        when(fixture.spaceFileMapper.listAll(1L)).thenReturn(List.of(personalFile));

        List<Map<String, Object>> spaces = ReflectionTestUtils.invokeMethod(
                fixture.service, "collectSpaces", 7L);

        assertNotNull(spaces);
        assertFalse(((List<?>) spaces.get(0).get("files")).isEmpty());
        assertFalse(spaces.get(1).containsKey("files"));
    }

    @Test
    void personalFilePayloadIsWrittenIntoArchive() throws Exception {
        Fixture fixture = new Fixture();
        byte[] payload = "real-content".getBytes(StandardCharsets.UTF_8);
        when(fixture.minio.getObjectStream("object-1")).thenReturn(new ByteArrayInputStream(payload));
        Map<String, Object> file = Map.of(
                "fileId", 9L, "fileUuid", "object-1", "name", "../secret.txt", "dir", false);

        byte[] zip = fixture.service.packageAsZip(Map.of("files", List.of(file)), "PERSONAL_FILES");

        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            byte[] extracted = null;
            while ((entry = input.getNextEntry()) != null) {
                if (!"export.json".equals(entry.getName())) extracted = input.readAllBytes();
            }
            assertArrayEquals(payload, extracted);
        }
    }

    private static SpaceVO space(Long id, String type, String role, Long ownerId) {
        SpaceVO space = new SpaceVO();
        space.setId(id);
        space.setType(type);
        space.setRole(role);
        space.setOwnerId(ownerId);
        space.setLifecycleState("ACTIVE");
        return space;
    }

    private static class Fixture {
        final DataExportJobMapper jobs = mock(DataExportJobMapper.class);
        final UnifiedTaskCenterService tasks = mock(UnifiedTaskCenterService.class);
        final MinioclientUtil minio = mock(MinioclientUtil.class);
        final LoginMapper login = mock(LoginMapper.class);
        final FileInfoMapper files = mock(FileInfoMapper.class);
        final KnowledgeChatSessionMapper sessions = mock(KnowledgeChatSessionMapper.class);
        final KnowledgeChatMessageMapper messages = mock(KnowledgeChatMessageMapper.class);
        final UserMemoryItemMapper memory = mock(UserMemoryItemMapper.class);
        final SpaceMapper spaceMapper = mock(SpaceMapper.class);
        final SpaceFileMapper spaceFileMapper = mock(SpaceFileMapper.class);
        final DataExportService service = new DataExportService(jobs, tasks, minio, new ObjectMapper(),
                login, files, sessions, messages, memory, spaceMapper, spaceFileMapper);
    }
}
