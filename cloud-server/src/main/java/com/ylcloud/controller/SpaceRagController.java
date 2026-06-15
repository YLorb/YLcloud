package com.ylcloud.controller;

import com.ylcloud.DTO.SpaceDocumentSearchDTO;
import com.ylcloud.DTO.SpaceRagConfigUpdateDTO;
import com.ylcloud.DTO.SpaceRagQueryDTO;
import com.ylcloud.Result;
import com.ylcloud.VO.SpaceDocumentSearchVO;
import com.ylcloud.VO.SpaceRagConfigVO;
import com.ylcloud.VO.SpaceRagDocumentVO;
import com.ylcloud.VO.SpaceRagQueryVO;
import com.ylcloud.VO.SpaceRagTaskVO;
import com.ylcloud.context.BaseContext;
import com.ylcloud.service.SpaceRagService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 空间 RAG 控制器。
 */
@RestController
@RequestMapping("/api/space/{spaceId}/rag")
public class SpaceRagController {
    private final SpaceRagService spaceRagService;

    /**
     * 初始化 SpaceRagController 对象。
     *
     * @param spaceRagService 空间 RAG 服务
     */
    public SpaceRagController(SpaceRagService spaceRagService) {
        this.spaceRagService = spaceRagService;
    }

    /**
     * 查询 getConfig 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @return 接口响应结果
     */
    @GetMapping("/config")
    public Result<SpaceRagConfigVO> getConfig(@PathVariable Long spaceId) {
        return Result.success(spaceRagService.getConfig(spaceId,BaseContext.getCurrentId()));
    }

    /**
     * 更新 updateConfig 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param dto 请求参数
     * @return 接口响应结果
     */
    @PutMapping("/config")
    public Result<SpaceRagConfigVO> updateConfig(@PathVariable Long spaceId,
                                                 @RequestBody @Valid SpaceRagConfigUpdateDTO dto) {
        return Result.success(spaceRagService.updateConfig(spaceId,dto,BaseContext.getCurrentId()));
    }

    /**
     * 执行 query 函数的业务处理。
     *
     * @param spaceId 空间 ID
     * @param dto 请求参数
     * @return 接口响应结果
     */
    @PostMapping("/query")
    public Result<SpaceRagQueryVO> query(@PathVariable Long spaceId,
                                         @RequestBody @Valid SpaceRagQueryDTO dto) {
        return Result.success(spaceRagService.query(spaceId,dto,BaseContext.getCurrentId()));
    }

    /**
     * 重建 rebuildSpace 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @return 接口响应结果
     */
    @PostMapping("/rebuild")
    public Result<Boolean> rebuildSpace(@PathVariable Long spaceId) {
        return Result.success(spaceRagService.rebuildSpace(spaceId,BaseContext.getCurrentId()));
    }

    /**
     * 重建 rebuildFile 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param spaceFileId 空间文件 ID
     * @return 接口响应结果
     */
    @PostMapping("/files/{spaceFileId}/rebuild")
    public Result<Boolean> rebuildFile(@PathVariable Long spaceId,
                                       @PathVariable Long spaceFileId) {
        return Result.success(spaceRagService.rebuildFile(spaceId,spaceFileId,BaseContext.getCurrentId()));
    }

    /**
     * 查询 listDocuments 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @return 接口响应结果
     */
    @GetMapping("/documents")
    public Result<List<SpaceRagDocumentVO>> listDocuments(@PathVariable Long spaceId) {
        return Result.success(spaceRagService.listDocuments(spaceId,BaseContext.getCurrentId()));
    }

    /**
     * 搜索 searchDocuments 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param dto 请求参数
     * @return 接口响应结果
     */
    @GetMapping("/documents/search")
    public Result<List<SpaceDocumentSearchVO>> searchDocuments(@PathVariable Long spaceId,
                                                               @ModelAttribute @Valid SpaceDocumentSearchDTO dto) {
        return Result.success(spaceRagService.searchDocuments(spaceId,dto,BaseContext.getCurrentId()));
    }

    /**
     * 查询 listTasks 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @return 接口响应结果
     */
    @GetMapping("/tasks")
    public Result<List<SpaceRagTaskVO>> listTasks(@PathVariable Long spaceId) {
        return Result.success(spaceRagService.listTasks(spaceId,BaseContext.getCurrentId()));
    }

    /**
     * 重试 retryTask 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param taskId 任务 ID
     * @return 接口响应结果
     */
    @PostMapping("/tasks/{taskId}/retry")
    public Result<Boolean> retryTask(@PathVariable Long spaceId,
                                     @PathVariable Long taskId) {
        return Result.success(spaceRagService.retryTask(spaceId,taskId,BaseContext.getCurrentId()));
    }

    /**
     * 重试 retryFailedTasks 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @return 接口响应结果
     */
    @PostMapping("/tasks/retry-failed")
    public Result<Boolean> retryFailedTasks(@PathVariable Long spaceId) {
        return Result.success(spaceRagService.retryFailedTasks(spaceId,BaseContext.getCurrentId()));
    }

    /**
     * 修复 repairSpaceVectors 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @return 接口响应结果
     */
    @PostMapping("/vectors/repair")
    public Result<Boolean> repairSpaceVectors(@PathVariable Long spaceId) {
        return Result.success(spaceRagService.repairSpaceVectors(spaceId,BaseContext.getCurrentId()));
    }

    /**
     * 修复 repairFileVectors 相关逻辑。
     *
     * @param spaceId 空间 ID
     * @param spaceFileId 空间文件 ID
     * @return 接口响应结果
     */
    @PostMapping("/files/{spaceFileId}/vectors/repair")
    public Result<Boolean> repairFileVectors(@PathVariable Long spaceId,
                                             @PathVariable Long spaceFileId) {
        return Result.success(spaceRagService.repairFileVectors(spaceId,spaceFileId,BaseContext.getCurrentId()));
    }
}
