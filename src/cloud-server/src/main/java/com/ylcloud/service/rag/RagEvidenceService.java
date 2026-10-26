package com.ylcloud.service.rag;

import org.springframework.beans.factory.annotation.Autowired;
import com.ylcloud.service.PreviewLinkService;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.Exception.NotFoundException;
import com.ylcloud.VO.*;
import com.ylcloud.authorization.SpaceFileAction;
import com.ylcloud.entity.*;
import com.ylcloud.mapper.RagEvidenceMapper;
import com.ylcloud.service.KnowledgeRevisionService;
import com.ylcloud.service.space.SpaceFileAccessService;
import com.ylcloud.service.space.SpacePermissionService;
import com.ylcloud.utils.MinioclientUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.*;

/** Server-owned evidence snapshots. Every read rechecks current authorization and revision policy. */
@Service
public class RagEvidenceService {
    @Autowired
    private PreviewLinkService previewLinks;

    private final RagEvidenceMapper mapper;
    private final KnowledgeRevisionService revisions;
    private final SpaceFileAccessService access;
    private final SpacePermissionService permissions;
    private final MinioclientUtil objects;
    private final ObjectMapper json;

    public RagEvidenceService(RagEvidenceMapper mapper, KnowledgeRevisionService revisions,
                              SpaceFileAccessService access, SpacePermissionService permissions,
                              MinioclientUtil objects, ObjectMapper json) {
        this.mapper=mapper; this.revisions=revisions; this.access=access;
        this.permissions=permissions; this.objects=objects; this.json=json;
    }

    public List<FileRagChunk> authorizedCandidates(Long spaceId,Long userId,List<FileRagChunk> chunks) {
        List<FileRagChunk> result=new ArrayList<>();
        for(FileRagChunk chunk:chunks) {
            KnowledgeRevision revision=revisions.revisionForChunk(spaceId,chunk.getId(),true);
            if(revision==null || !Objects.equals(revision.getFileHash(),chunk.getFileHash())) continue;
            try { access.requireNodeAction(spaceId,revision.getSpaceFileId(),userId,SpaceFileAction.READ); }
            catch(BaseException denied) { continue; }
            result.add(chunk);
        }
        return result;
    }

    @Transactional
    public List<SpaceRagCitationVO> createCitations(Long spaceId,Long userId,
            List<FileRagChunk> chunks,List<RagAnswerEvidence> evidence) {
        Map<Long,FileRagChunk> byId=new HashMap<>();
        for(FileRagChunk chunk:chunks) byId.put(chunk.getId(),chunk);
        List<SpaceRagCitationVO> result=new ArrayList<>();
        for(RagAnswerEvidence item:evidence) {
            FileRagChunk chunk=byId.get(item.getChunkId());
            if(chunk==null || item.getContent()==null || item.getContent().isBlank() || !chunk.getContent().startsWith(item.getContent()))
                throw new BaseException("INVALID_EVIDENCE");
            KnowledgeRevision revision=revisions.revisionForChunk(spaceId,chunk.getId(),true);
            if(revision==null || !Objects.equals(chunk.getFileHash(),revision.getFileHash()))
                throw new BaseException("STALE_REVISION");
            SpaceFile file=access.requireNodeAction(spaceId,revision.getSpaceFileId(),userId,SpaceFileAction.READ);
            String id=UUID.randomUUID().toString();
            SpaceRagCitationVO citation=new SpaceRagCitationVO();
            citation.setIndex(result.size()+1); citation.setSpaceId(spaceId);
            citation.setEvidenceId(id); citation.setSourceEvidenceId(item.getEvidenceId());
            citation.setRevisionId(revision.getId()); citation.setFileVersionId(revision.getFileVersionId());
            citation.setFileHash(revision.getFileHash()); citation.setSpaceFileId(revision.getSpaceFileId());
            citation.setChunkId(chunk.getId()); citation.setFileName(file.getFileName());
            citation.setContentSummary(item.getContent().substring(0,Math.min(240,item.getContent().length())));
            citation.setStale(revision.getSupersededAt()!=null);
            applyLocator(citation,chunk.getMetadata());
            String base="/api/space/"+spaceId+"/rag/evidence/"+id;
            citation.setPreviewUrl(previewLinks.shorten(base+"/preview"+(citation.getPageStart()==null ? "" : "#page="+citation.getPageStart())));
            citation.setDownloadUrl(base+"/download");
            RagEvidenceRecord record=new RagEvidenceRecord();
            record.setId(id); record.setUserId(userId); record.setSpaceId(spaceId);
            record.setSpaceFileId(revision.getSpaceFileId()); record.setRevisionId(revision.getId());
            record.setChunkId(chunk.getId()); record.setFileHash(revision.getFileHash());
            record.setContent(item.getContent()); record.setCitationJson(write(citation));
            record.setCreatedAt(LocalDateTime.now()); mapper.insertEvidence(record);
            result.add(citation);
        }
        return result;
    }

    public void validateCitations(Long userId,List<SpaceRagCitationVO> citations,boolean forNewQuery) {
        for(SpaceRagCitationVO citation:citations) require(citation.getSpaceId(),citation.getEvidenceId(),userId,forNewQuery);
    }

    public RagEvidenceRecord require(Long spaceId,String id,Long userId,boolean forNewQuery) {
        RagEvidenceRecord evidence=mapper.getEvidence(id,userId);
        if(evidence==null || !Objects.equals(spaceId,evidence.getSpaceId())) throw unavailable();
        access.requireNodeAction(spaceId,evidence.getSpaceFileId(),userId,SpaceFileAction.READ);
        KnowledgeRevision revision=revisions.safeRevisionById(spaceId,evidence.getSpaceFileId(),evidence.getRevisionId(),forNewQuery);
        if(revision==null || !Objects.equals(revision.getId(),evidence.getRevisionId())) throw unavailable();
        return evidence;
    }

    public Map<String,Object> detail(Long spaceId,String id,Long userId) {
        RagEvidenceRecord evidence=require(spaceId,id,userId,false);
        return Map.of("citation",read(evidence.getCitationJson(),SpaceRagCitationVO.class),"content",evidence.getContent());
    }

    public void stream(Long spaceId,String id,Long userId,boolean download,HttpServletResponse response) throws Exception {
        RagEvidenceRecord evidence=require(spaceId,id,userId,false);
        KnowledgeRevision revision=revisions.safeRevisionById(spaceId,evidence.getSpaceFileId(),evidence.getRevisionId(),false);
        if(revision==null || revision.getMinioVersionId()==null || revision.getMinioVersionId().isBlank()) throw unavailable();
        SpaceRagCitationVO citation=read(evidence.getCitationJson(),SpaceRagCitationVO.class);
        String name=citation.getFileName()==null ? "evidence" : citation.getFileName();
        // No redirect/presigned URL: keep the current authorization boundary on every new request.
        response.setHeader("Cache-Control","private, no-store");
        response.setHeader("X-Content-Type-Options","nosniff");
        response.setHeader("Content-Security-Policy","sandbox");
        boolean pdf=name.toLowerCase(Locale.ROOT).endsWith(".pdf");
        response.setContentType(!download && pdf ? "application/pdf" : "application/octet-stream");
        response.setHeader("Content-Disposition",(download || !pdf ? "attachment" : "inline")
                + "; filename*=UTF-8''"+java.net.URLEncoder.encode(name,java.nio.charset.StandardCharsets.UTF_8).replace("+","%20"));
        try(InputStream input=objects.getObjectStream(revision.getFileUuid(),revision.getMinioVersionId())) {
            input.transferTo(response.getOutputStream());
        }
    }

    @Transactional
    public String storeAnswer(Long userId,List<Long> spaceIds,String content,List<SpaceRagCitationVO> citations,String status) {
        for(Long spaceId:spaceIds) permissions.requireMember(spaceId,userId);
        validateCitations(userId,citations,true);
        RagTrustedAnswer answer=new RagTrustedAnswer();
        answer.setId(UUID.randomUUID().toString()); answer.setUserId(userId); answer.setContent(content);
        answer.setAnswerStatus(status!=null && Set.of("ANSWERED","PARTIAL","REFUSED","UNAVAILABLE").contains(status)
                ? status : "SERVER_RESULT");
        answer.setCitationsJson(write(citations)); answer.setSpaceIdsJson(write(spaceIds));
        answer.setCreatedAt(LocalDateTime.now()); mapper.insertAnswer(answer);
        return answer.getId();
    }

    public RagTrustedAnswer readAnswer(String answerId,Long userId) {
        if(answerId==null || answerId.isBlank()) throw unavailable();
        RagTrustedAnswer answer=mapper.getAnswer(answerId,userId);
        if(answer==null) throw unavailable();
        try {
            for(Long spaceId:json.readValue(answer.getSpaceIdsJson(),new TypeReference<List<Long>>(){}))
                permissions.requireMember(spaceId,userId);
            List<SpaceRagCitationVO> citations=json.readValue(answer.getCitationsJson(),new TypeReference<List<SpaceRagCitationVO>>(){});
            validateCitations(userId,citations,false);
        } catch(BaseException denied) { throw denied; }
        catch(Exception malformed) { throw unavailable(); }
        return answer;
    }

    public void protectMessage(KnowledgeChatMessage message,KnowledgeChatMessageVO vo) {
        if(!"assistant".equals(message.getRole())) return;
        vo.setAnswerId(message.getTrustedAnswerId());
        if(message.getTrustedAnswerId()==null && Set.of("QUEUED","RUNNING").contains(
                message.getTaskStatus()==null ? "" : message.getTaskStatus())) {
            vo.setContent("正在生成回答…"); vo.setCitationsJson("[]"); vo.setVerificationStatus("PENDING"); return;
        }
        if(Set.of("FAILED","CANCELED").contains(message.getTaskStatus()==null ? "" : message.getTaskStatus())) {
            boolean cancelled="CANCELED".equals(message.getTaskStatus());
            vo.setContent(cancelled ? "用户已取消回答。" : "回答生成失败，请重试。");
            vo.setCitationsJson("[]"); vo.setAnswerId(null);
            // Exception text can contain a provider response or restricted source text.
            vo.setErrorMessage(cancelled ? "用户已取消回答" : "回答校验或生成未完成，请重试。");
            vo.setVerificationStatus(message.getTaskStatus()); return;
        }
        try {
            RagTrustedAnswer answer=readAnswer(message.getTrustedAnswerId(),message.getUserId());
            vo.setContent(answer.getContent()); vo.setCitationsJson(answer.getCitationsJson());
            vo.setVerificationStatus("ANSWERED".equals(answer.getAnswerStatus()) ? "VERIFIED"
                    : answer.getAnswerStatus()==null ? "SERVER_RESULT" : answer.getAnswerStatus());
        } catch(BaseException unavailable) {
            vo.setContent("该回答的依据已不可访问或尚未验证，请重新提问。");
            vo.setCitationsJson("[]"); vo.setErrorMessage(null); vo.setVerificationStatus("UNAVAILABLE");
        }
    }

    private void applyLocator(SpaceRagCitationVO citation,String metadata) {
        citation.setLocatorStatus("UNLOCATED");
        if(metadata==null) return;
        try {
            JsonNode root=json.readTree(metadata);
            int start=root.path("pageStart").asInt(0),end=root.path("pageEnd").asInt(0);
            if(start>0) { citation.setPageStart(start); citation.setPageEnd(end>=start ? end : start); citation.setLocatorStatus("PAGE"); }
            JsonNode headings=root.path("headingPath");
            if(headings.isArray()) {
                List<String> labels=new ArrayList<>(); headings.forEach(h -> {if(h.isTextual()) labels.add(h.asText());});
                citation.setHeading(String.join(" / ",labels));
            }
        } catch(Exception ignored) { /* Missing locator is explicit; never guess a page. */ }
    }
    private String write(Object value) { try{return json.writeValueAsString(value);}catch(Exception ex){throw new BaseException("证据序列化失败");} }
    private <T> T read(String value,Class<T> type) {try{return json.readValue(value,type);}catch(Exception ex){throw unavailable();} }
    private NotFoundException unavailable() { return new NotFoundException("引用不可用或无权访问"); }
}
