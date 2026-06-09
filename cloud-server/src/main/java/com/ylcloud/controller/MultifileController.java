package com.ylcloud.controller;

import com.ylcloud.DTO.MultifileDTO;
import com.ylcloud.Result;
import com.ylcloud.VO.ChunkStatusVO;
import com.ylcloud.VO.FileMergeReqVO;
import com.ylcloud.VO.FileVO;
import com.ylcloud.VO.InitifileVO;
import com.ylcloud.context.BaseContext;
import com.ylcloud.service.MultifileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 大文件分片上传控制器。
 */
@Slf4j
@RestController
@RequestMapping("/api/file/multipart")
public class MultifileController {
    @Autowired
    private MultifileService multifileService;

    /**
     * 初始化分片上传任务，支持秒传和断点续传任务复用。
     *
     * @param multifileDTO 分片上传初始化参数
     * @return 初始化结果，包含上传任务 ID、秒传标记和已上传分片列表
     */
    @PostMapping("/init")
    public Result<InitifileVO> init(@RequestBody MultifileDTO multifileDTO) {
        Long userId = BaseContext.getCurrentId();
        return Result.success(multifileService.initfile(multifileDTO,userId));
    }

    /**
     * 上传单个文件分片。
     *
     * @param file 当前分片文件
     * @param uploadId 上传任务 ID
     * @param chunkIndex 当前分片序号，从 0 开始
     * @param chunkMd5 当前分片 MD5，可为空；传入时后端会校验
     * @return 上传是否成功
     */
    @PostMapping("/chunk")
    public Result<Boolean> uploadChunk(@RequestParam("file") MultipartFile file,
                                       @RequestParam("uploadId") String uploadId,
                                       @RequestParam("chunkIndex") Integer chunkIndex,
                                       @RequestParam(value = "chunkMd5", required = false) String chunkMd5) {
        Long userId = BaseContext.getCurrentId();
        return Result.success(multifileService.uploadChunk(file,uploadId,chunkIndex,chunkMd5,userId));
    }

    /**
     * 查询分片上传进度，用于前端断点续传。
     *
     * @param uploadId 上传任务 ID
     * @return 已上传分片信息
     */
    @GetMapping("/status/{uploadId}")
    public Result<ChunkStatusVO> status(@PathVariable String uploadId) {
        Long userId = BaseContext.getCurrentId();
        return Result.success(multifileService.status(uploadId,userId));
    }

    /**
     * 合并已经上传完成的所有分片。
     *
     * @param reqVO 分片合并请求参数
     * @return 合并后的文件信息
     */
    @PostMapping("/merge")
    public Result<FileVO> merge(@RequestBody FileMergeReqVO reqVO) {
        Long userId = BaseContext.getCurrentId();
        return Result.success(multifileService.merge(reqVO.getUploadId(),userId));
    }
}
