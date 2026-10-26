package com.ylcloud.share;

import com.ylcloud.utils.MinioclientUtil;
import java.io.DataOutputStream;
import java.nio.file.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShareDownloadDeliveryTest {
    @TempDir Path directory;
    private final ShareRepository repository = mock(ShareRepository.class);
    private final MinioclientUtil storage = mock(MinioclientUtil.class);
    private final ShareLinkService service = new ShareLinkService(repository,null,null,null,null,null,null,storage,null,new com.ylcloud.utils.ShortLinkUtil(0));
    @Test void directShareUsesApprovedImmutableManifestAndCapsLifetime() throws Exception {
        var link = new ShareModels.Link(); link.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(45));
        when(repository.byCode("code")).thenReturn(link);
        var manifest = directory.resolve("approved.manifest");
        try (var out = new DataOutputStream(Files.newOutputStream(manifest))) {
            out.writeUTF("file.txt"); out.writeUTF("authorized-object"); out.writeUTF("immutable-v2");
        }
        var response = new MockHttpServletResponse();
        when(storage.redirectDownload(eq("authorized-object"),eq("immutable-v2"),eq("file.txt"),intThat(ttl -> ttl>0 && ttl<=45),same(response))).thenReturn(true);
        try (var download = new ShareLinkService.Download("file.txt",false,manifest)) {
            assertTrue(service.redirectDownload("code",download,response));
        }
        assertFalse(Files.exists(manifest));
    }
    @Test void expiredAndRevokedSharesNeverIssueDirectLinks() throws Exception {
        var link = new ShareModels.Link(); link.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        when(repository.byCode("code")).thenReturn(link);
        var download = new ShareLinkService.Download("file",false,directory.resolve("unused"));
        assertThrows(ShareModels.Expired.class, () -> service.redirectDownload("code",download,new MockHttpServletResponse()));
        link.setExpiresAt(null); link.setRevokedAt(LocalDateTime.now(ZoneOffset.UTC));
        assertThrows(ShareModels.Expired.class, () -> service.redirectDownload("code",download,new MockHttpServletResponse()));
        verifyNoInteractions(storage);
    }
    @Test void directoryDownloadsStayAsAuthorizedStreamingArchives() throws Exception {
        assertFalse(service.redirectDownload("code",new ShareLinkService.Download("folder",true,directory.resolve("unused")),new MockHttpServletResponse()));
        verifyNoInteractions(storage,repository);
    }
}
