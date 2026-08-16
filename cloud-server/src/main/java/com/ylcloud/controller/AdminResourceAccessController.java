package com.ylcloud.controller;

import com.ylcloud.Result;
import com.ylcloud.VO.FilePreviewVO;
import com.ylcloud.VO.FileVO;
import com.ylcloud.authorization.AccessSubject;
import com.ylcloud.authorization.ResourceAction;
import com.ylcloud.authorization.ResourceType;
import com.ylcloud.context.BaseContext;
import com.ylcloud.service.AdminPermissionService;
import com.ylcloud.service.AuthorizationService;
import com.ylcloud.service.FileService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Explicit cross-user read surface. Normal file controllers always retain the
 * caller's own user id and therefore cannot be used for administrator access.
 */
@RestController
@RequestMapping("/api/admin/resource-access")
@RequiredArgsConstructor
public class AdminResourceAccessController {
    private final AdminPermissionService adminPermissionService;
    private final AuthorizationService authorizationService;
    private final FileService fileService;

    @GetMapping("/users/{userId}/files")
    public Result<List<FileVO>> listUserFiles(
            @PathVariable Long userId,
            @RequestParam(value = "parentId", defaultValue = "0") Long parentId
    ) {
        adminPermissionService.requireAdmin();
        authorizationService.require(
                AccessSubject.user(BaseContext.getCurrentId()),
                ResourceType.USER_PRIVATE,
                userId,
                ResourceAction.READ
        );
        return Result.success(fileService.listFiles(parentId,userId));
    }

    @GetMapping("/users/{userId}/files/{fileUuid}/preview")
    public Result<FilePreviewVO> previewUserFile(
            @PathVariable Long userId,
            @PathVariable String fileUuid,
            @RequestParam Long parentId
    ) {
        require(userId,ResourceAction.READ);
        return Result.success(fileService.previewFile(fileUuid,parentId));
    }

    @GetMapping("/users/{userId}/files/{fileUuid}/preview/stream")
    public void previewUserFileStream(
            @PathVariable Long userId,
            @PathVariable String fileUuid,
            @RequestParam Long parentId,
            HttpServletResponse response
    ) {
        require(userId,ResourceAction.READ);
        fileService.previewFileStream(fileUuid,parentId,response);
    }

    @GetMapping("/users/{userId}/files/{fileUuid}/download")
    public void downloadUserFile(
            @PathVariable Long userId,
            @PathVariable String fileUuid,
            @RequestParam Long parentId,
            HttpServletResponse response
    ) {
        require(userId,ResourceAction.DOWNLOAD);
        fileService.downloadFile(fileUuid,parentId,response);
    }

    private void require(Long userId, ResourceAction action) {
        adminPermissionService.requireAdmin();
        authorizationService.require(
                AccessSubject.user(BaseContext.getCurrentId()),
                ResourceType.USER_PRIVATE,
                userId,
                action
        );
    }
}
