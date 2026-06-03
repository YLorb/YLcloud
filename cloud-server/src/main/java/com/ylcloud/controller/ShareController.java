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
     * 创建分享控制器。
     *
     * @param fileService 文件业务服务
     */
    public ShareController(FileService fileService) {
        this.fileService = fileService;
    }

    /**
     * 根据分享码查询公开展示的分享文件；目录会展示直接子内容，文件会展示可下载和可预览入口。
     *
     * @param shareCode 分享码
     * @return 分享文件公开摘要
     */
    @GetMapping("/{shareCode}")
    public Result<ShareFileVO> getSharedFile(@PathVariable String shareCode) {
        return Result.success(fileService.getSharedFile(shareCode));
    }

    /**
     * 根据分享码下载公开分享中的文件。
     *
     * @param shareCode 分享码
     * @param fileId 分享目录下被选择的文件节点 ID；分享单文件时可不传
     * @param response HTTP 响应对象
     */
    @GetMapping(value = "/{shareCode}", params = "download=true")
    public void downloadSharedFile(@PathVariable String shareCode,
                                   @RequestParam(required = false) Long fileId,
                                   HttpServletResponse response) {
        fileService.downloadSharedFile(shareCode,fileId,response);
    }

    /**
     * 根据分享码获取公开分享文件的预览信息。
     *
     * @param shareCode 分享码
     * @param fileId 分享目录下被选择的文件节点 ID；分享单文件时可不传
     * @return 分享文件预览信息
     */
    @GetMapping(value = "/{shareCode}", params = "preview=true")
    public Result<FilePreviewVO> previewSharedFile(@PathVariable String shareCode,
                                                   @RequestParam(required = false) Long fileId) {
        return Result.success(fileService.previewSharedFile(shareCode,fileId));
    }

    /**
     * 根据分享码输出公开分享文件的预览流。
     *
     * @param shareCode 分享码
     * @param fileId 分享目录下被选择的文件节点 ID；分享单文件时可不传
     * @param response HTTP 响应对象
     */
    @GetMapping(value = "/{shareCode}", params = "stream=true")
    public void previewSharedFileStream(@PathVariable String shareCode,
                                        @RequestParam(required = false) Long fileId,
                                        HttpServletResponse response) {
        fileService.previewSharedFileStream(shareCode,fileId,response);
    }
}
