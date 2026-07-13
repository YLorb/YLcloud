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

    /**
     * 初始化 SpaceFileController 对象。
     *
     * @param spaceFileService 空间文件服务
     * @param fileVersionService 文件版本服务
     */
    public SpaceFileController(SpaceFileService spaceFileService, FileVersionService fileVersionService) {
        this.spaceFileService = spaceFileService;
        this.fileVersionService = fileVersionService;
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
    @DeleteMapping("/{fileId}")
    public Result<Boolean> remove(@PathVariable Long spaceId, @PathVariable Long fileId) {
        return Result.success(spaceFileService.removeFile(spaceId,fileId,BaseContext.getCurrentId()));
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
