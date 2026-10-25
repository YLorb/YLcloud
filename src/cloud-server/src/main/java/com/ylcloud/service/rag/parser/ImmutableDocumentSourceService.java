package com.ylcloud.service.rag.parser;

import com.ylcloud.entity.File;
import com.ylcloud.entity.FileVersion;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.FileVersionMapper;
import com.ylcloud.service.InitialFileVersionService;
import com.ylcloud.Exception.ConflictException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;

/** Establishes a short SQL-locked immutable object pin before any parser/network call. */
@Service
public class ImmutableDocumentSourceService {
    private final FileInfoMapper files;
    private final FileVersionMapper versions;
    private final InitialFileVersionService initial;
    public ImmutableDocumentSourceService(FileInfoMapper files,FileVersionMapper versions,InitialFileVersionService initial) {
        this.files=files; this.versions=versions; this.initial=initial;
    }
    @Transactional
    public void pin(File requested) {
        if(requested==null || requested.getFileUuid()==null || requested.getHash()==null)
            throw new ConflictException("缺少文件版本身份，拒绝解析");
        File current=files.getPhysicalFileForUpdate(requested.getFileUuid());
        if(current==null || !Objects.equals(current.getHash(),requested.getHash()))
            throw new ConflictException("文件版本已变化，重新调度解析");
        FileVersion version=versions.findByHash(requested.getFileUuid(),requested.getHash());
        if(version==null) {
            initial.ensureInitialVersion(requested.getFileUuid(),requested.getName(),requested.getUserId());
            version=versions.findByHash(requested.getFileUuid(),requested.getHash());
        }
        if(version==null || version.getMinioVersionId()==null || version.getMinioVersionId().isBlank())
            throw new ConflictException("缺少可核验的不可变对象版本");
        requested.setMinioVersionId(version.getMinioVersionId());
    }
    public static String requireVersion(File file) {
        if(file==null || file.getMinioVersionId()==null || file.getMinioVersionId().isBlank())
            throw new IllegalArgumentException("Parser must read an immutable object version");
        return file.getMinioVersionId();
    }
}
