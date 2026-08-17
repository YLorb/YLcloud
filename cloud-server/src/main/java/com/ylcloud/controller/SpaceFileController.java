package com.ylcloud.controller;

import com.ylcloud.DTO.SpaceFileImportDTO;
import com.ylcloud.DTO.SpaceFileMoveDTO;
import com.ylcloud.DTO.SpaceFileRenameDTO;
import com.ylcloud.DTO.SpaceFileDeleteConfirmDTO;
import com.ylcloud.DTO.SpaceFileImportBatchCreateDTO;
import com.ylcloud.DTO.SpaceFolderCreateDTO;
import com.ylcloud.DTO.VersionSettingDTO;
import com.ylcloud.Result;
import com.ylcloud.VO.FilePreviewVO;
import com.ylcloud.VO.FileVersionVO;
import com.ylcloud.VO.SpaceFileVO;
import com.ylcloud.VO.SpaceFileDeletePreviewVO;
import com.ylcloud.VO.SpaceFileDeleteTaskVO;
import com.ylcloud.VO.SpaceFileImportBatchVO;
import com.ylcloud.context.BaseContext;
import com.ylcloud.service.FileVersionService;
import com.ylcloud.service.SpaceFileService;
import com.ylcloud.service.SpaceFileImportBatchService;
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
    private final SpaceFileImportBatchService importBatchService;

    /**
     * 初始化 SpaceFileController 对象。
     *
     * @param spaceFileService 空间文件服务
     * @param fileVersionService 文件版本服务
     */
    public SpaceFileController(SpaceFileService spaceFileService, FileVersionService fileVersionService,
                               SpaceFileImportBatchService importBatchService) {
        this.spaceFileService = spaceFileService;
        this.fileVersionService = fileVersionService;
        this.importBatchService = importBatchService;
    }

    /**
     * 查询 list 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param parentId 父级 ID
     * @return 接口响应结果
     */
    @GetMapping("/list")
    public Result<List<SpaceFileVO>> list(@PathVariable Long spaceId,
                                          @RequestParam(value = "parentId", required = false) Long parentId) {
        return Result.success(spaceFileService.listFiles(spaceId,parentId,BaseContext.getCurrentId()));
    }

    /**
     * 执行 tree 函数的业务处理。
     *
     * @param spaceId 空间 ID
     * @return 接口响应结果
     */
    @GetMapping("/tree")
    public Result<List<SpaceFileVO>> tree(@PathVariable Long spaceId) {
        return Result.success(spaceFileService.tree(spaceId,BaseContext.getCurrentId()));
    }

    @GetMapping("/{folderId}/ancestors")
    public Result<List<SpaceFileVO>> ancestors(@PathVariable Long spaceId, @PathVariable Long folderId) {
        return Result.success(spaceFileService.ancestors(spaceId,folderId,BaseContext.getCurrentId()));
    }

    @GetMapping("/search")
    public Result<List<SpaceFileVO>> search(@PathVariable Long spaceId,
                                            @RequestParam("q") String query,
                                            @RequestParam(value = "limit",required = false) Integer limit) {
        return Result.success(spaceFileService.search(spaceId,query,limit,BaseContext.getCurrentId()));
    }

    @GetMapping("/duplicates-report")
    public Result<List<SpaceFileVO>> duplicatesReport(@PathVariable Long spaceId) {
        return Result.success(spaceFileService.duplicatesReport(spaceId,BaseContext.getCurrentId()));
    }

    @PutMapping("/{fileId}/rename")
    public Result<SpaceFileVO> rename(@PathVariable Long spaceId,
                                      @PathVariable Long fileId,
                                      @RequestBody @Valid SpaceFileRenameDTO dto) {
        return Result.success(spaceFileService.rename(spaceId,fileId,dto,BaseContext.getCurrentId()));
    }

    @PutMapping("/{fileId}/move")
    public Result<SpaceFileVO> move(@PathVariable Long spaceId,
                                    @PathVariable Long fileId,
                                    @RequestBody @Valid SpaceFileMoveDTO dto) {
        return Result.success(spaceFileService.move(spaceId,fileId,dto,BaseContext.getCurrentId()));
    }

    /**
     * 查询 listVersionEnabledFiles 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @return 接口响应结果
     */
    @GetMapping("/version-enabled")
    public Result<List<SpaceFileVO>> listVersionEnabledFiles(@PathVariable Long spaceId) {
        return Result.success(spaceFileService.listVersionEnabledFiles(spaceId,BaseContext.getCurrentId()));
    }

    /**
     * 创建 createFolder 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param dto 请求参数
     * @return 接口响应结果
     */
    @PostMapping("/folder")
    public Result<SpaceFileVO> createFolder(@PathVariable Long spaceId,
                                            @RequestBody @Valid SpaceFolderCreateDTO dto) {
        return Result.success(spaceFileService.createFolder(spaceId,dto,BaseContext.getCurrentId()));
    }

    /**
     * 执行 importFile 函数的业务处理。
     *
     * @param spaceId 空间 ID
     * @param dto 请求参数
     * @return 接口响应结果
     */
    @PostMapping("/import")
    public Result<SpaceFileVO> importFile(@PathVariable Long spaceId,
                                          @RequestBody @Valid SpaceFileImportDTO dto) {
        return Result.success(spaceFileService.importUserFile(spaceId,dto,BaseContext.getCurrentId()));
    }

    /**
     * 上传 upload 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param file 文件对象
     * @param parentId 父级目录 ID
     * @param name 可选文件名
     * @return 接口响应结果
     */
    @PostMapping("/upload")
    public Result<SpaceFileVO> upload(@PathVariable Long spaceId,
                                      @RequestParam("file") MultipartFile file,
                                      @RequestParam(value = "parentId", required = false) Long parentId,
                                      @RequestParam(value = "name", required = false) String name,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return Result.success(spaceFileService.uploadFile(
                spaceId,file,parentId,name,BaseContext.getCurrentId(),idempotencyKey));
    }

    /**
     * 移除 remove 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param fileId 文件 ID
     * @return 接口响应结果
     */
    @PostMapping("/{fileId}/deletion-preview")
    public Result<SpaceFileDeletePreviewVO> deletionPreview(@PathVariable Long spaceId,@PathVariable Long fileId) {
        return Result.success(spaceFileService.deletionPreview(spaceId,fileId,BaseContext.getCurrentId()));
    }

    @PostMapping("/import-batches")
    public Result<SpaceFileImportBatchVO> createImportBatch(@PathVariable Long spaceId,
                                                            @RequestBody @Valid SpaceFileImportBatchCreateDTO dto) {
        return Result.success(importBatchService.create(spaceId,dto,BaseContext.getCurrentId()));
    }

    @GetMapping("/import-batches/{batchId}")
    public Result<SpaceFileImportBatchVO> getImportBatch(@PathVariable Long spaceId,@PathVariable Long batchId) {
        return Result.success(importBatchService.get(spaceId,batchId,BaseContext.getCurrentId()));
    }

    @DeleteMapping("/{fileId}")
    public Result<SpaceFileDeleteTaskVO> remove(@PathVariable Long spaceId, @PathVariable Long fileId,
                                                @RequestBody @Valid SpaceFileDeleteConfirmDTO dto) {
        return Result.success(spaceFileService.removeFile(spaceId,fileId,dto,BaseContext.getCurrentId()));
    }

    /**
     * 更新 updateVersionSetting 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param fileId 文件 ID
     * @param dto 请求参数
     * @return 接口响应结果
     */
    @PutMapping("/{fileId}/version-setting")
    public Result<SpaceFileVO> updateVersionSetting(@PathVariable Long spaceId,
                                                    @PathVariable Long fileId,
                                                    @RequestBody @Valid VersionSettingDTO dto) {
        return Result.success(spaceFileService.updateVersionEnabled(spaceId,fileId,dto.getVersionEnabled(),BaseContext.getCurrentId()));
    }

    /**
     * 预览 preview 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param fileId 文件 ID
     * @return 接口响应结果
     */
    @GetMapping("/{fileId}/preview")
    public Result<FilePreviewVO> preview(@PathVariable Long spaceId, @PathVariable Long fileId) {
        return Result.success(spaceFileService.previewFile(spaceId,fileId,BaseContext.getCurrentId()));
    }

    /**
     * 预览 previewStream 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param fileId 文件 ID
     * @param response 响应对象
     */
    @GetMapping("/{fileId}/preview/stream")
    public void previewStream(@PathVariable Long spaceId,
                              @PathVariable Long fileId,
                              HttpServletResponse response) {
        spaceFileService.previewFileStream(spaceId,fileId,BaseContext.getCurrentId(),response);
    }

    /**
     * 下载 download 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param fileId 文件 ID
     * @param response 响应对象
     */
    @GetMapping("/{fileId}/download")
    public void download(@PathVariable Long spaceId,
                         @PathVariable Long fileId,
                         HttpServletResponse response) {
        spaceFileService.downloadFile(spaceId,fileId,BaseContext.getCurrentId(),response);
    }

    /**
     * 上传 uploadVersion 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param fileId 文件 ID
     * @param file 文件对象
     * @param changeNote 变更说明
     * @return 接口响应结果
     */
    @PostMapping("/{fileId}/versions")
    public Result<FileVersionVO> uploadVersion(@PathVariable Long spaceId,
                                               @PathVariable Long fileId,
                                               @RequestParam("file") MultipartFile file,
                                               @RequestParam(value = "changeNote", required = false) String changeNote,
                                               @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return Result.success(fileVersionService.uploadSpaceFileVersion(
                spaceId,fileId,file,changeNote,BaseContext.getCurrentId(),idempotencyKey));
    }

    /**
     * 查询 listVersions 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param fileId 文件 ID
     * @return 接口响应结果
     */
    @GetMapping("/{fileId}/versions")
    public Result<List<FileVersionVO>> listVersions(@PathVariable Long spaceId,
                                                    @PathVariable Long fileId) {
        return Result.success(fileVersionService.listSpaceFileVersions(spaceId,fileId,BaseContext.getCurrentId()));
    }

    /**
     * 预览 previewVersion 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param fileId 文件 ID
     * @param versionRecordId 版本记录 ID
     * @return 接口响应结果
     */
    @GetMapping("/{fileId}/versions/{versionRecordId}/preview")
    public Result<FilePreviewVO> previewVersion(@PathVariable Long spaceId,
                                                @PathVariable Long fileId,
                                                @PathVariable Long versionRecordId) {
        return Result.success(fileVersionService.previewVersion(spaceId,fileId,versionRecordId,BaseContext.getCurrentId()));
    }

    /**
     * 预览 previewVersionStream 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param fileId 文件 ID
     * @param versionRecordId 版本记录 ID
     * @param response 响应对象
     */
    @GetMapping("/{fileId}/versions/{versionRecordId}/preview/stream")
    public void previewVersionStream(@PathVariable Long spaceId,
                                     @PathVariable Long fileId,
                                     @PathVariable Long versionRecordId,
                                     HttpServletResponse response) {
        fileVersionService.previewVersionStream(spaceId,fileId,versionRecordId,BaseContext.getCurrentId(),response);
    }

    /**
     * 下载 downloadVersion 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param fileId 文件 ID
     * @param versionRecordId 版本记录 ID
     * @param response 响应对象
     */
    @GetMapping("/{fileId}/versions/{versionRecordId}/download")
    public void downloadVersion(@PathVariable Long spaceId,
                                @PathVariable Long fileId,
                                @PathVariable Long versionRecordId,
                                HttpServletResponse response) {
        fileVersionService.downloadVersion(spaceId,fileId,versionRecordId,BaseContext.getCurrentId(),response);
    }

    /**
     * 恢复 restoreVersion 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param fileId 文件 ID
     * @param versionRecordId 版本记录 ID
     * @param changeNote 变更说明
     * @return 接口响应结果
     */
    @PostMapping("/{fileId}/versions/{versionRecordId}/restore")
    public Result<FileVersionVO> restoreVersion(@PathVariable Long spaceId,
                                                @PathVariable Long fileId,
                                                @PathVariable Long versionRecordId,
                                                @RequestParam(value = "changeNote", required = false) String changeNote,
                                                @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return Result.success(fileVersionService.restoreVersion(
                spaceId,fileId,versionRecordId,changeNote,BaseContext.getCurrentId(),idempotencyKey));
    }
}
