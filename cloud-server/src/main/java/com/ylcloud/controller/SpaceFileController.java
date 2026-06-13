package com.ylcloud.controller;

import com.ylcloud.DTO.SpaceFileImportDTO;
import com.ylcloud.DTO.SpaceFolderCreateDTO;
import com.ylcloud.DTO.VersionSettingDTO;
import com.ylcloud.Result;
import com.ylcloud.VO.FilePreviewVO;
import com.ylcloud.VO.FileVersionVO;
import com.ylcloud.VO.SpaceFileVO;
import com.ylcloud.context.BaseContext;
import com.ylcloud.service.FileVersionService;
import com.ylcloud.service.SpaceFileService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 空间文件树控制器。
 */
@RestController
@RequestMapping("/api/space/{spaceId}/files")
public class SpaceFileController {
    private final SpaceFileService spaceFileService;
    private final FileVersionService fileVersionService;

    public SpaceFileController(SpaceFileService spaceFileService, FileVersionService fileVersionService) {
        this.spaceFileService = spaceFileService;
        this.fileVersionService = fileVersionService;
    }

    @GetMapping("/list")
    public Result<List<SpaceFileVO>> list(@PathVariable Long spaceId,
                                          @RequestParam(value = "parentId", required = false) Long parentId) {
        return Result.success(spaceFileService.listFiles(spaceId,parentId,BaseContext.getCurrentId()));
    }

    @GetMapping("/tree")
    public Result<List<SpaceFileVO>> tree(@PathVariable Long spaceId) {
        return Result.success(spaceFileService.tree(spaceId,BaseContext.getCurrentId()));
    }

    @GetMapping("/version-enabled")
    public Result<List<SpaceFileVO>> listVersionEnabledFiles(@PathVariable Long spaceId) {
        return Result.success(spaceFileService.listVersionEnabledFiles(spaceId,BaseContext.getCurrentId()));
    }

    @PostMapping("/folder")
    public Result<SpaceFileVO> createFolder(@PathVariable Long spaceId,
                                            @RequestBody @Valid SpaceFolderCreateDTO dto) {
        return Result.success(spaceFileService.createFolder(spaceId,dto,BaseContext.getCurrentId()));
    }

    @PostMapping("/import")
    public Result<SpaceFileVO> importFile(@PathVariable Long spaceId,
                                          @RequestBody @Valid SpaceFileImportDTO dto) {
        return Result.success(spaceFileService.importUserFile(spaceId,dto,BaseContext.getCurrentId()));
    }

    @DeleteMapping("/{fileId}")
    public Result<Boolean> remove(@PathVariable Long spaceId, @PathVariable Long fileId) {
        return Result.success(spaceFileService.removeFile(spaceId,fileId,BaseContext.getCurrentId()));
    }

    @PutMapping("/{fileId}/version-setting")
    public Result<SpaceFileVO> updateVersionSetting(@PathVariable Long spaceId,
                                                    @PathVariable Long fileId,
                                                    @RequestBody @Valid VersionSettingDTO dto) {
        return Result.success(spaceFileService.updateVersionEnabled(spaceId,fileId,dto.getVersionEnabled(),BaseContext.getCurrentId()));
    }

    @GetMapping("/{fileId}/preview")
    public Result<FilePreviewVO> preview(@PathVariable Long spaceId, @PathVariable Long fileId) {
        return Result.success(spaceFileService.previewFile(spaceId,fileId,BaseContext.getCurrentId()));
    }

    @GetMapping("/{fileId}/preview/stream")
    public void previewStream(@PathVariable Long spaceId,
                              @PathVariable Long fileId,
                              HttpServletResponse response) {
        spaceFileService.previewFileStream(spaceId,fileId,BaseContext.getCurrentId(),response);
    }

    @GetMapping("/{fileId}/download")
    public void download(@PathVariable Long spaceId,
                         @PathVariable Long fileId,
                         HttpServletResponse response) {
        spaceFileService.downloadFile(spaceId,fileId,BaseContext.getCurrentId(),response);
    }

    @PostMapping("/{fileId}/versions")
    public Result<FileVersionVO> uploadVersion(@PathVariable Long spaceId,
                                               @PathVariable Long fileId,
                                               @RequestParam("file") MultipartFile file,
                                               @RequestParam(value = "changeNote", required = false) String changeNote) {
        return Result.success(fileVersionService.uploadSpaceFileVersion(spaceId,fileId,file,changeNote,BaseContext.getCurrentId()));
    }

    @GetMapping("/{fileId}/versions")
    public Result<List<FileVersionVO>> listVersions(@PathVariable Long spaceId,
                                                    @PathVariable Long fileId) {
        return Result.success(fileVersionService.listSpaceFileVersions(spaceId,fileId,BaseContext.getCurrentId()));
    }

    @GetMapping("/{fileId}/versions/{versionRecordId}/preview")
    public Result<FilePreviewVO> previewVersion(@PathVariable Long spaceId,
                                                @PathVariable Long fileId,
                                                @PathVariable Long versionRecordId) {
        return Result.success(fileVersionService.previewVersion(spaceId,fileId,versionRecordId,BaseContext.getCurrentId()));
    }

    @GetMapping("/{fileId}/versions/{versionRecordId}/preview/stream")
    public void previewVersionStream(@PathVariable Long spaceId,
                                     @PathVariable Long fileId,
                                     @PathVariable Long versionRecordId,
                                     HttpServletResponse response) {
        fileVersionService.previewVersionStream(spaceId,fileId,versionRecordId,BaseContext.getCurrentId(),response);
    }

    @GetMapping("/{fileId}/versions/{versionRecordId}/download")
    public void downloadVersion(@PathVariable Long spaceId,
                                @PathVariable Long fileId,
                                @PathVariable Long versionRecordId,
                                HttpServletResponse response) {
        fileVersionService.downloadVersion(spaceId,fileId,versionRecordId,BaseContext.getCurrentId(),response);
    }

    @PostMapping("/{fileId}/versions/{versionRecordId}/restore")
    public Result<FileVersionVO> restoreVersion(@PathVariable Long spaceId,
                                                @PathVariable Long fileId,
                                                @PathVariable Long versionRecordId,
                                                @RequestParam(value = "changeNote", required = false) String changeNote) {
        return Result.success(fileVersionService.restoreVersion(spaceId,fileId,versionRecordId,changeNote,BaseContext.getCurrentId()));
    }
}
