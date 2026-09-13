package com.ylcloud.controller;

import com.ylcloud.DTO.AdminFullTextSearchDTO;
import com.ylcloud.Result;
import com.ylcloud.VO.AdminFullTextSearchResultVO;
import com.ylcloud.service.AdminPermissionService;
import com.ylcloud.service.admin.AdminFullTextSearchService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin 全文搜索控制器。
 */
@RestController
@RequestMapping("/api/admin/search")
public class AdminSearchController {

    private final AdminFullTextSearchService adminFullTextSearchService;
    private final AdminPermissionService adminPermissionService;

    public AdminSearchController(AdminFullTextSearchService adminFullTextSearchService,
                                  AdminPermissionService adminPermissionService) {
        this.adminFullTextSearchService = adminFullTextSearchService;
        this.adminPermissionService = adminPermissionService;
    }

    /**
     * 全文搜索文件。
     * <p>
     * 搜索逻辑：
     * 1. 词语匹配：LIKE '%query%' on chunk.content
     * 2. 关键词检索：LIKE '%query%' on chunk.content（排除已命中）
     * 3. 向量同义检索：Qdrant cosine similarity search
     * <p>
     * 合并去重后排序：词语匹配 > 关键词 > 向量同义检索
     * 返回最多 50 条结果。
     *
     * @param dto 搜索请求参数
     * @return 搜索结果
     */
    @GetMapping("/files")
    public Result<AdminFullTextSearchResultVO> searchFiles(@Valid @ModelAttribute AdminFullTextSearchDTO dto) {
        adminPermissionService.requireAdmin();
        return Result.success(adminFullTextSearchService.search(dto));
    }
}
