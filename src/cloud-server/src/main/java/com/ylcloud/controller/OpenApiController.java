package com.ylcloud.controller;

import com.ylcloud.DTO.KnowledgeChatQueryCreateDTO;
import com.ylcloud.DTO.KnowledgeChatSessionCreateDTO;
import com.ylcloud.DTO.OpenApiFileMoveDTO;
import com.ylcloud.DTO.OpenApiFolderCreateDTO;
import com.ylcloud.DTO.SpaceRagQueryDTO;
import com.ylcloud.DTO.UserFileDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.VO.FileVO;
import com.ylcloud.VO.KnowledgeChatMessageVO;
import com.ylcloud.VO.KnowledgeChatSessionVO;
import com.ylcloud.VO.OpenApiEnvelope;
import com.ylcloud.VO.OpenApiPage;
import com.ylcloud.VO.SpaceRagQueryVO;
import com.ylcloud.VO.SpaceVO;
import com.ylcloud.VO.QuotaUsageVO;
import com.ylcloud.context.OpenApiContext;
import com.ylcloud.interceptor.OpenApiKeyInterceptor;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.security.ApiKeyPrincipal;
import com.ylcloud.service.FileService;
import com.ylcloud.service.OpenApiKnowledgeChatService;
import com.ylcloud.service.KnowledgeChatSessionService;
import com.ylcloud.service.OpenApiVersionPolicyService;
import com.ylcloud.service.QuotaService;
import com.ylcloud.service.space.SpaceRagService;
import com.ylcloud.service.space.SpaceService;
import com.ylcloud.service.UserApiKeyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/api/v1","/api/open"})
@RequiredArgsConstructor
public class OpenApiController {
    private final UserApiKeyService apiKeys;
    private final OpenApiVersionPolicyService versionPolicy;
    private final FileService fileService;
    private final FileInfoMapper fileInfoMapper;
    private final SpaceService spaceService;
    private final SpaceRagService spaceRagService;
    private final KnowledgeChatSessionService chatSessionService;
    private final OpenApiKnowledgeChatService openApiChats;
    private QuotaService quotas;
    @Autowired private com.ylcloud.service.metering.ApiTokenMeteringService metering;

    @GetMapping("/usage/records")
    public OpenApiEnvelope<List<Map<String,Object>>> usageRecords(@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int pageSize,HttpServletRequest request) {
        var principal=OpenApiContext.require();
        return success(metering.records(principal.userId(),principal.keyId(),page,pageSize),request);
    }

    @GetMapping("/capabilities")
    public OpenApiEnvelope<Map<String,Object>> capabilities(HttpServletRequest request) {
        ApiKeyPrincipal principal = OpenApiContext.require();
        return success(Map.of("scopes",principal.scopes(),"spaceIds",principal.spaceIds(),
                "driveRootFileId",principal.driveRootFileId() == null ? 0 : principal.driveRootFileId()),request);
    }

    @GetMapping("/usage")
    public OpenApiEnvelope<QuotaUsageVO> usage(HttpServletRequest request) {
        return success(quotas.userUsage(OpenApiContext.require().userId()),request);
    }

    @Autowired(required=false)
    public void setQuotaService(QuotaService quotas) { this.quotas=quotas; }

    @GetMapping("/files")
    public OpenApiEnvelope<OpenApiPage<FileVO>> files(@RequestParam(required = false) Long parentId,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(required = false) Integer pageSize,
                                                       HttpServletRequest request) {
        ApiKeyPrincipal principal = OpenApiContext.require();
        Long effectiveParent = parentId == null ? principal.driveRootFileId() : parentId;
        if(effectiveParent == null) throw new BaseException(403,"API Key 未启用云盘 Scope");
        apiKeys.requireDriveResource(principal,effectiveParent,false);
        int size = versionPolicy.pageSize(pageSize);
        if(page < 1) throw new BaseException("page 必须大于 0");
        return success(OpenApiPage.of(fileService.listFiles(effectiveParent,principal.userId()),page,size),request);
    }

    @PostMapping("/files/upload")
    public OpenApiEnvelope<FileVO> upload(@RequestPart("file") MultipartFile file,
                                          @RequestParam Long parentId,
                                          @RequestHeader("Idempotency-Key") String idempotencyKey,
                                          HttpServletRequest request) {
        ApiKeyPrincipal principal = OpenApiContext.require();
        apiKeys.requireDriveResource(principal,parentId,true);
        if(idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length()>128)
            throw new BaseException("Idempotency-Key 必须为 1-128 字符");
        return success(fileService.upload(file,parentId,idempotencyKey),request);
    }

    @PostMapping("/files/folders")
    public OpenApiEnvelope<FileVO> createFolder(@RequestBody @Valid OpenApiFolderCreateDTO dto,
                                                 @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                 HttpServletRequest request) {
        ApiKeyPrincipal principal = OpenApiContext.require();
        apiKeys.requireDriveResource(principal,dto.getParentId(),true);
        return success(fileService.makefile(1,dto.getParentId(),dto.getName(),null,idempotencyKey),request);
    }

    @PutMapping("/files/{fileId}/move")
    public OpenApiEnvelope<Boolean> move(@PathVariable Long fileId,@RequestBody @Valid OpenApiFileMoveDTO dto,
                                         HttpServletRequest request) {
        ApiKeyPrincipal principal = OpenApiContext.require();
        apiKeys.requireDriveMove(principal,fileId,dto.getTargetParentId());
        return success(fileService.movefiles(fileId,dto.getTargetParentId()),request);
    }

    @DeleteMapping("/files/{fileId}")
    public OpenApiEnvelope<Boolean> recycle(@PathVariable Long fileId,HttpServletRequest request) {
        ApiKeyPrincipal principal = OpenApiContext.require();
        apiKeys.requireDriveResource(principal,fileId,true);
        UserFileDTO file = fileInfoMapper.getByFileId(fileId,principal.userId());
        if(file == null || file.getParentId() == null) throw new BaseException(404,"文件不存在");
        return success(fileService.deleteFiles(file.getFileUuid(),file.getParentId()),request);
    }

    @GetMapping("/spaces")
    public OpenApiEnvelope<OpenApiPage<SpaceVO>> spaces(@RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(required = false) Integer pageSize,
                                                         HttpServletRequest request) {
        ApiKeyPrincipal principal = OpenApiContext.require();
        int size = versionPolicy.pageSize(pageSize);
        if(page < 1) throw new BaseException("page 必须大于 0");
        List<SpaceVO> values = spaceService.listMySpaces(principal.userId()).stream()
                .filter(space -> principal.spaceIds().contains(space.getId())).toList();
        return success(OpenApiPage.of(values,page,size),request);
    }

    @PostMapping("/spaces/{spaceId}/knowledge/query")
    public OpenApiEnvelope<SpaceRagQueryVO> knowledgeQuery(@PathVariable Long spaceId,
                                                            @RequestBody @Valid SpaceRagQueryDTO dto,
                                                            HttpServletRequest request) {
        ApiKeyPrincipal principal = OpenApiContext.require();
        apiKeys.requireSpace(principal,spaceId,false);
        var usage=com.ylcloud.service.metering.ApiUsageContext.current();
        if(usage!=null) usage.authorize(List.of(spaceId),false);
        var result=spaceRagService.query(spaceId,dto,principal.userId());
        if(usage!=null) usage.check();
        return success(result,request);
    }

    @PostMapping("/assistant/sessions")
    public OpenApiEnvelope<KnowledgeChatSessionVO> createAssistantSession(
            @RequestBody @Valid KnowledgeChatSessionCreateDTO dto,HttpServletRequest request) {
        ApiKeyPrincipal principal = OpenApiContext.require();
        requireSpaces(principal,dto.getSpaceIds(),false);
        return success(chatSessionService.create(principal.userId(),dto),request);
    }

    @PostMapping("/assistant/sessions/{sessionId}/queries")
    public OpenApiEnvelope<KnowledgeChatMessageVO> assistantQuery(@PathVariable Long sessionId,
            @RequestBody @Valid KnowledgeChatQueryCreateDTO dto,
            @RequestHeader("Idempotency-Key") String idempotencyKey,HttpServletRequest request) {
        ApiKeyPrincipal principal = OpenApiContext.require();
        return success(openApiChats.submit(principal,sessionId,dto,idempotencyKey,false),request);
    }

    @PostMapping("/agent/sessions/{sessionId}/queries")
    public OpenApiEnvelope<KnowledgeChatMessageVO> agentQuery(@PathVariable Long sessionId,
            @RequestBody @Valid KnowledgeChatQueryCreateDTO dto,
            @RequestHeader("Idempotency-Key") String idempotencyKey,HttpServletRequest request) {
        ApiKeyPrincipal principal = OpenApiContext.require();
        return success(openApiChats.submit(principal,sessionId,dto,idempotencyKey,true),request);
    }

    @GetMapping("/assistant/sessions/{sessionId}/queries/{messageId}")
    public OpenApiEnvelope<KnowledgeChatMessageVO> assistantResult(@PathVariable Long sessionId,@PathVariable Long messageId,
            HttpServletRequest request,HttpServletResponse response) {
        response.setHeader("Cache-Control","private, no-store");
        return success(openApiChats.result(OpenApiContext.require(),sessionId,messageId,false),request);
    }

    @GetMapping("/agent/sessions/{sessionId}/queries/{messageId}")
    public OpenApiEnvelope<KnowledgeChatMessageVO> agentResult(@PathVariable Long sessionId,@PathVariable Long messageId,
            HttpServletRequest request,HttpServletResponse response) {
        response.setHeader("Cache-Control","private, no-store");
        return success(openApiChats.result(OpenApiContext.require(),sessionId,messageId,true),request);
    }

    private void requireSpaces(ApiKeyPrincipal principal,List<Long> spaceIds,boolean agent) {
        if(spaceIds == null || spaceIds.isEmpty()) throw new BaseException("至少选择一个 Space");
        spaceIds.forEach(spaceId -> apiKeys.requireSpace(principal,spaceId,agent));
    }

    private <T> OpenApiEnvelope<T> success(T data,HttpServletRequest request) {
        String traceId = String.valueOf(request.getAttribute(OpenApiKeyInterceptor.TRACE_ATTRIBUTE));
        boolean unversioned = Boolean.TRUE.equals(request.getAttribute(OpenApiKeyInterceptor.UNVERSIONED_ATTRIBUTE));
        return OpenApiEnvelope.success(data,traceId,"v1",unversioned);
    }
}
