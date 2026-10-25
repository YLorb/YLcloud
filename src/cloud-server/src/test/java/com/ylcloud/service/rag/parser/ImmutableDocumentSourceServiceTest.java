package com.ylcloud.service.rag.parser;

import com.ylcloud.entity.File;
import com.ylcloud.entity.FileVersion;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.FileVersionMapper;
import com.ylcloud.service.InitialFileVersionService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImmutableDocumentSourceServiceTest {
    final FileInfoMapper files=mock(FileInfoMapper.class);
    final FileVersionMapper versions=mock(FileVersionMapper.class);
    final InitialFileVersionService initial=mock(InitialFileVersionService.class);
    final ImmutableDocumentSourceService service=new ImmutableDocumentSourceService(files,versions,initial);

    @Test void pinsExactVersionAfterLockingCommittedMetadata() {
        File requested=file("hash"); when(files.getPhysicalFileForUpdate("file")).thenReturn(file("hash"));
        FileVersion version=new FileVersion(); version.setMinioVersionId("immutable");
        when(versions.findByHash("file","hash")).thenReturn(version);
        service.pin(requested);
        var order=inOrder(files,versions);
        order.verify(files).getPhysicalFileForUpdate("file"); order.verify(versions).findByHash("file","hash");
        assertEquals("immutable",requested.getMinioVersionId());
        verifyNoInteractions(initial);
    }

    @Test void staleHashFailsBeforeAnyObjectSnapshotLookup() {
        when(files.getPhysicalFileForUpdate("file")).thenReturn(file("new"));
        assertThrows(com.ylcloud.Exception.ConflictException.class,()->service.pin(file("old")));
        verifyNoInteractions(versions,initial);
    }

    @Test void absentImmutableSnapshotCannotFallBackToLatestObject() {
        when(files.getPhysicalFileForUpdate("file")).thenReturn(file("hash"));
        assertThrows(com.ylcloud.Exception.ConflictException.class,()->service.pin(file("hash")));
        assertThrows(IllegalArgumentException.class,()->ImmutableDocumentSourceService.requireVersion(file("hash")));
    }

    @Test void parserCacheSeparatesImmutableVersionsAndLegacyEntries() {
        String first=HybridDocumentParser.cacheKey("structured-v3","v1");
        assertNotEquals("structured-v3",first);
        assertNotEquals(first,HybridDocumentParser.cacheKey("structured-v3","v2"));
        assertNotEquals(first,HybridDocumentParser.cacheKey("structured-v4","v1"));
    }

    private File file(String hash) { return File.builder().fileUuid("file").hash(hash).name("a.txt").userId(1L).build(); }
}
