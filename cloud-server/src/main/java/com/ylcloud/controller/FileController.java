package com.ylcloud.controller;

import com.ylcloud.Result;
import com.ylcloud.DTO.BatchFileOperationDTO;
import com.ylcloud.VO.FileVO;
import com.ylcloud.VO.FilePreviewVO;
import com.ylcloud.context.BaseContext;
import com.ylcloud.service.FileService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@Slf4j
@RequestMapping("/api/file")
public class FileController {

    @Autowired
    private FileService fileService;

    /**
     * 初始化 FileController 对象。
     *
     * @param fileService 文件服务
     */
    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    /**
     * 上传 upload 相关逻辑。
     *
     * @param file 文件对象
     * @param parentId 父级 ID
     * @return 接口响应结果
     */
    @PostMapping("/upload")
    public Result<FileVO> upload(@RequestParam("file") MultipartFile file,
                                 @RequestParam(value = "parentId",defaultValue = "0") Long parentId,
                                 @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return Result.success(fileService.upload(file,parentId,idempotencyKey));
    }

    /**
     * 下载 download 相关逻辑。
     *
     * @param fileUuid 文件 UUID
     * @param parentId 父级 ID
     * @param response 响应对象
     */
    @GetMapping("/download/{fileUuid}")
    public void download(@PathVariable String fileUuid, @RequestParam Long parentId, HttpServletResponse response) {
        fileService.downloadFile(fileUuid,parentId,response);
    }

    /**
     * 执行 share 函数的业务处理。
     *
     * @param fileUuid 文件 UUID
     * @param parentId 父级 ID
     * @return 接口响应结果
     */
    @PostMapping("/share/{fileUuid}")
    public Result<String> share(@PathVariable String fileUuid,
                                @RequestParam(value = "parentId", defaultValue = "0") Long parentId) {
        return Result.success(fileService.shareFile(fileUuid,parentId));
    }

    /**
     * 重命名 rename 相关逻辑。
     *
     * @param fileUuid 文件 UUID
     * @param parentId 父级 ID
     * @param newName 新名称
     * @return 接口响应结果
     */
    @PutMapping("/rename/{fileUuid}")
    public Result<FileVO> rename(@PathVariable String fileUuid,
                                 @RequestParam(value = "parentId", defaultValue = "0") Long parentId,
                                 @RequestParam("newName") String newName) {
        return Result.success(fileService.renameFile(fileUuid,parentId,newName));
    }

    /**
     * 删除 delete 相关逻辑。
     *
     * @param fileUuid 文件 UUID
     * @param parentId 父级 ID
     * @return 接口响应结果
     */
    @DeleteMapping("/{fileUuid}")
    public Result<Boolean> delete(@PathVariable String fileUuid,
                                  @RequestParam(value = "parentId", defaultValue = "0") Long parentId) {
        return Result.success(fileService.deleteFiles(fileUuid,parentId));
    }

    /**
     * 查询 listRecycleFiles 相关逻辑。
     * @return 接口响应结果
     */
    @GetMapping("/recycle")
    public Result<List<FileVO>> listRecycleFiles() {
        return Result.success(fileService.listRecycleFiles());
    }

    /**
     * 恢复 restoreRecycleFile 相关逻辑。
     *
     * @param fileId 文件 ID
     * @return 接口响应结果
     */
    @PutMapping("/recycle/{fileId}/restore")
    public Result<Boolean> restoreRecycleFile(@PathVariable Long fileId) {
        return Result.success(fileService.restoreRecycleFile(fileId));
    }

    /**
     * 删除 deleteRecycleFilePermanently 相关逻辑。
     *
     * @param fileId 文件 ID
     * @return 接口响应结果
     */
    @DeleteMapping("/recycle/{fileId}")
    public Result<Boolean> deleteRecycleFilePermanently(@PathVariable Long fileId) {
        return Result.success(fileService.deleteRecycleFilePermanently(fileId));
    }

    /**
     * 预览 previewFile 相关逻辑。
     *
     * @param fileUuid 文件 UUID
     * @param parentId 父级 ID
     * @return 接口响应结果
     */
    @GetMapping("/preview/{fileUuid}")
    public Result<FilePreviewVO> previewFile(@PathVariable String fileUuid,
                                             @RequestParam(value = "parentId", defaultValue = "0") Long parentId) {
        return Result.success(fileService.previewFile(fileUuid,parentId));
    }

    /**
     * 预览 previewFileStream 相关逻辑。
     *
     * @param fileUuid 文件 UUID
     * @param parentId 父级 ID
     * @param response 响应对象
     */
    @GetMapping("/preview/{fileUuid}/stream")
    public void previewFileStream(@PathVariable String fileUuid,
                                  @RequestParam(value = "parentId", defaultValue = "0") Long parentId,
                                  HttpServletResponse response) {
        fileService.previewFileStream(fileUuid,parentId,response);
    }

    /**
     * 执行 makefile 函数的业务处理。
     *
     * @param isDir 方法入参
     * @param parentId 父级 ID
     * @param name 名称
     * @param type 类型
     * @return 接口响应结果
     */
    @PostMapping("/{isDir}")
    public Result<FileVO> makefile(@PathVariable int isDir,
                                   @RequestParam(value = "parentId", defaultValue = "0") Long parentId,
                                   @RequestParam("name") String name,
                                   @RequestParam(value = "type", required = false) String type,
                                   @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return Result.success(fileService.makefile(isDir,parentId,name,type,idempotencyKey));
    }

    /**
     * 查询 listFiles 相关逻辑。
     *
     * @param parentId 父级 ID
     * @return 接口响应结果
     */
    @GetMapping("/list")
    public Result<List<FileVO>> listFiles(@RequestParam(value = "parentId",defaultValue = "0") Long parentId) {
        Long userId = BaseContext.getCurrentId();
        List<FileVO> files = fileService.listFiles(parentId,userId);
        return Result.success(files);
    }

    @GetMapping("/category")
    public Result<List<FileVO>> listFilesByCategory(@RequestParam String category,
                                                    @RequestParam(required = false) String keyword) {
        return Result.success(fileService.listFilesByCategory(category,keyword,BaseContext.getCurrentId()));
    }

    @DeleteMapping("/batch")
    public Result<Boolean> batchDelete(@RequestBody @jakarta.validation.Valid BatchFileOperationDTO dto) {
        return Result.success(fileService.batchDeleteFiles(dto.getFileIds()));
    }

    @PutMapping("/batch/move")
    public Result<Boolean> batchMove(@RequestBody @jakarta.validation.Valid BatchFileOperationDTO dto) {
        return Result.success(fileService.batchMoveFiles(dto.getFileIds(),dto.getTargetParentId()));
    }

    @PutMapping("/batch/copy")
    public Result<Boolean> batchCopy(@RequestBody @jakarta.validation.Valid BatchFileOperationDTO dto) {
        return Result.success(fileService.batchCopyFiles(dto.getFileIds(),dto.getTargetParentId()));
    }

    /**
     * 执行 bucket_exists 函数的业务处理。
     * @return 接口响应结果
     */
    @GetMapping("/bucket")
    public Result<Boolean> bucket_exists() {
        return Result.success(fileService.bucketExists());
    }

    /**
     * 执行 movefiles 函数的业务处理。
     *
     * @param sourceplace 方法入参
     * @param targetplace 方法入参
     * @return 接口响应结果
     */
    @PutMapping("/move")
    public Result<Boolean> movefiles(@RequestParam Long sourceplace, @RequestParam Long targetplace) {
        return Result.success(fileService.movefiles(sourceplace,targetplace));
    }

    /**
     * 复制 copyfiles 相关逻辑。
     *
     * @param sourceplace 方法入参
     * @param targetplace 方法入参
     * @return 接口响应结果
     */
    @PutMapping("/copy")
    public Result<Boolean> copyfiles(@RequestParam Long sourceplace, @RequestParam Long targetplace) {
        return Result.success(fileService.copyfiles(sourceplace,targetplace));
    }
}
