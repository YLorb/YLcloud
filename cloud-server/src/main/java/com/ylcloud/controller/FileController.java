package com.ylcloud.controller;

import com.ylcloud.Result;
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
     * 创建文件控制器。
     *
     * @param fileService 文件业务服务
     */
    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    /**
     * 上传文件到指定父目录。
     *
     * @param file 上传的文件对象
     * @param parentId 父目录 ID，默认根目录
     * @return 上传后的文件信息
     */
    @PostMapping("/upload")
    public Result<FileVO> upload(@RequestParam("file") MultipartFile file,
                                 @RequestParam(value = "parentId",defaultValue = "0") Long parentId) {
        return Result.success(fileService.upload(file,parentId));
    }

    /**
     * 下载当前用户指定目录下的文件。
     *
     * @param fileUuid 文件唯一标识
     * @param parentId 文件所在父目录 ID
     * @param response HTTP 响应对象
     */
    @GetMapping("/download/{fileUuid}")
    public void download(@PathVariable String fileUuid, @RequestParam Long parentId, HttpServletResponse response) {
        fileService.downloadFile(fileUuid,parentId,response);
    }

    /**
     * 创建或复用文件分享链接。
     *
     * @param fileUuid 文件唯一标识
     * @param parentId 文件所在父目录 ID，默认根目录
     * @return 分享访问路径
     */
    @PostMapping("/share/{fileUuid}")
    public Result<String> share(@PathVariable String fileUuid,
                                @RequestParam(value = "parentId", defaultValue = "0") Long parentId) {
        return Result.success(fileService.shareFile(fileUuid,parentId));
    }

    /**
     * 重命名文件或目录。
     *
     * @param fileUuid 文件唯一标识
     * @param parentId 文件所在父目录 ID，默认根目录
     * @param newName 新文件名
     * @return 重命名后的文件信息
     */
    @PutMapping("/rename/{fileUuid}")
    public Result<FileVO> rename(@PathVariable String fileUuid,
                                 @RequestParam(value = "parentId", defaultValue = "0") Long parentId,
                                 @RequestParam("newName") String newName) {
        return Result.success(fileService.renameFile(fileUuid,parentId,newName));
    }

    /**
     * 软删除文件或目录，文件会进入回收站。
     *
     * @param fileUuid 文件唯一标识
     * @param parentId 文件所在父目录 ID，默认根目录
     * @return 删除是否成功
     */
    @DeleteMapping("/{fileUuid}")
    public Result<Boolean> delete(@PathVariable String fileUuid,
                                  @RequestParam(value = "parentId", defaultValue = "0") Long parentId) {
        return Result.success(fileService.deleteFiles(fileUuid,parentId));
    }

    /**
     * 查询当前用户回收站中的顶层文件或目录。
     *
     * @return 回收站文件列表
     */
    @GetMapping("/recycle")
    public Result<List<FileVO>> listRecycleFiles() {
        return Result.success(fileService.listRecycleFiles());
    }

    /**
     * 从回收站恢复文件或目录。
     *
     * @param fileId 用户文件关系 ID
     * @return 恢复是否成功
     */
    @PutMapping("/recycle/{fileId}/restore")
    public Result<Boolean> restoreRecycleFile(@PathVariable Long fileId) {
        return Result.success(fileService.restoreRecycleFile(fileId));
    }

    /**
     * 彻底删除回收站中的文件或目录。
     *
     * @param fileId 用户文件关系 ID
     * @return 彻底删除是否成功
     */
    @DeleteMapping("/recycle/{fileId}")
    public Result<Boolean> deleteRecycleFilePermanently(@PathVariable Long fileId) {
        return Result.success(fileService.deleteRecycleFilePermanently(fileId));
    }

    /**
     * 获取文件预览信息。
     *
     * @param fileUuid 文件唯一标识
     * @param parentId 文件所在父目录 ID，默认根目录
     * @return 文件预览信息
     */
    @GetMapping("/preview/{fileUuid}")
    public Result<FilePreviewVO> previewFile(@PathVariable String fileUuid,
                                             @RequestParam(value = "parentId", defaultValue = "0") Long parentId) {
        return Result.success(fileService.previewFile(fileUuid,parentId));
    }

    /**
     * 输出文件预览流。
     *
     * @param fileUuid 文件唯一标识
     * @param parentId 文件所在父目录 ID，默认根目录
     * @param response HTTP 响应对象
     */
    @GetMapping("/preview/{fileUuid}/stream")
    public void previewFileStream(@PathVariable String fileUuid,
                                  @RequestParam(value = "parentId", defaultValue = "0") Long parentId,
                                  HttpServletResponse response) {
        fileService.previewFileStream(fileUuid,parentId,response);
    }

    /**
     * 新建文件或目录。
     *
     * @param isDir 是否目录，1 表示目录，0 表示文件
     * @param parentId 父目录 ID，默认根目录
     * @param name 文件或目录名称
     * @param type 文件类型
     * @return 新建后的文件信息
     */
    @PostMapping("/{isDir}")
    public Result<FileVO> makefile(@PathVariable int isDir,
                                   @RequestParam(value = "parentId", defaultValue = "0") Long parentId,
                                   @RequestParam("name") String name,
                                   @RequestParam("type") String type) {
        return Result.success(fileService.makefile(isDir,parentId,name,type));
    }

    /**
     * 查询指定父目录下的文件列表。
     *
     * @param parentId 父目录 ID，默认根目录
     * @return 文件列表
     */
    @GetMapping("/list")
    public Result<List<FileVO>> listFiles(@RequestParam(value = "parentId",defaultValue = "0") Long parentId) {
        Long userId = BaseContext.getCurrentId();
        List<FileVO> files = fileService.listFiles(parentId,userId);
        return Result.success(files);
    }

    /**
     * 检查默认对象存储桶是否存在。
     *
     * @return 存储桶是否存在
     */
    @GetMapping("/bucket")
    public Result<Boolean> bucket_exists() {
        return Result.success(fileService.bucketExists());
    }

    /**
     * 移动文件或目录到目标目录。
     *
     * @param sourceplace 源文件或目录 ID
     * @param targetplace 目标目录 ID
     * @return 移动是否成功
     */
    @PutMapping("/move")
    public Result<Boolean> movefiles(@RequestParam Long sourceplace, @RequestParam Long targetplace) {
        return Result.success(fileService.movefiles(sourceplace,targetplace));
    }

    /**
     * 复制文件或目录到目标目录。
     *
     * @param sourceplace 源文件或目录 ID
     * @param targetplace 目标目录 ID
     * @return 复制是否成功
     */
    @PutMapping("/copy")
    public Result<Boolean> copyfiles(@RequestParam Long sourceplace, @RequestParam Long targetplace) {
        return Result.success(fileService.copyfiles(sourceplace,targetplace));
    }
}
