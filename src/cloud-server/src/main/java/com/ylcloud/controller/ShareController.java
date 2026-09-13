package com.ylcloud.controller;

import com.ylcloud.Result;
import com.ylcloud.VO.FilePreviewVO;
import com.ylcloud.VO.ShareFileVO;
import com.ylcloud.service.FileService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/share")
public class ShareController {

    private final FileService fileService;

    /**
     * 初始化 ShareController 对象。
     *
     * @param fileService 文件服务
     */
    public ShareController(FileService fileService) {
        this.fileService = fileService;
    }

    /**
     * 查询 getSharedFile 相关逻辑。
     *
     * @param shareCode 分享码
     * @return 接口响应结果
     */
    @GetMapping("/{shareCode}")
    public Result<ShareFileVO> getSharedFile(@PathVariable String shareCode) {
        return Result.success(fileService.getSharedFile(shareCode));
    }

    /**
     * 下载 downloadSharedFile 相关逻辑。
     *
     * @param shareCode 分享码
     * @param fileId 文件 ID
     * @param response 响应对象
     */
    @GetMapping(value = "/{shareCode}", params = "download=true")
    public void downloadSharedFile(@PathVariable String shareCode,
                                   @RequestParam(required = false) Long fileId,
                                   HttpServletResponse response) {
        fileService.downloadSharedFile(shareCode,fileId,response);
    }

    /**
     * 预览 previewSharedFile 相关逻辑。
     *
     * @param shareCode 分享码
     * @param fileId 文件 ID
     * @return 接口响应结果
     */
    @GetMapping(value = "/{shareCode}", params = "preview=true")
    public Result<FilePreviewVO> previewSharedFile(@PathVariable String shareCode,
                                                   @RequestParam(required = false) Long fileId) {
        return Result.success(fileService.previewSharedFile(shareCode,fileId));
    }

    /**
     * 预览 previewSharedFileStream 相关逻辑。
     *
     * @param shareCode 分享码
     * @param fileId 文件 ID
     * @param response 响应对象
     */
    @GetMapping(value = "/{shareCode}", params = "stream=true")
    public void previewSharedFileStream(@PathVariable String shareCode,
                                        @RequestParam(required = false) Long fileId,
                                        HttpServletResponse response) {
        fileService.previewSharedFileStream(shareCode,fileId,response);
    }
}
