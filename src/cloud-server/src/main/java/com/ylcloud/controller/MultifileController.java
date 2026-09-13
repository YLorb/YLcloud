package com.ylcloud.controller;

import com.ylcloud.DTO.MultifileDTO;
import com.ylcloud.Result;
import com.ylcloud.VO.ChunkStatusVO;
import com.ylcloud.VO.FileMergeReqVO;
import com.ylcloud.VO.FileVO;
import com.ylcloud.VO.InitifileVO;
import com.ylcloud.context.BaseContext;
import com.ylcloud.service.MultifileService;
import jakarta.validation.Valid;
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
     * 初始化 init 相关逻辑。
     *
     * @param multifileDTO 分片上传初始化参数
     * @return 接口响应结果
     */
    @PostMapping("/init")
    public Result<InitifileVO> init(@RequestBody @Valid MultifileDTO multifileDTO) {
        Long userId = BaseContext.getCurrentId();
        return Result.success(multifileService.initfile(multifileDTO,userId));
    }

    /**
     * 上传 uploadChunk 相关逻辑。
     *
     * @param file 文件对象
     * @param uploadId 方法入参
     * @param chunkIndex 方法入参
     * @param chunkMd5 方法入参
     * @return 接口响应结果
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
     * 执行 status 函数的业务处理。
     *
     * @param uploadId 方法入参
     * @return 接口响应结果
     */
    @GetMapping("/status/{uploadId}")
    public Result<ChunkStatusVO> status(@PathVariable String uploadId) {
        Long userId = BaseContext.getCurrentId();
        return Result.success(multifileService.status(uploadId,userId));
    }

    /**
     * 合并 merge 相关逻辑。
     *
     * @param reqVO 请求参数
     * @return 接口响应结果
     */
    @PostMapping("/merge")
    public Result<FileVO> merge(@RequestBody @Valid FileMergeReqVO reqVO) {
        Long userId = BaseContext.getCurrentId();
        return Result.success(multifileService.merge(reqVO.getUploadId(),userId));
    }
}
