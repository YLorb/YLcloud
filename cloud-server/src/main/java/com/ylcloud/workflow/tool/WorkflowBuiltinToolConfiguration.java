package com.ylcloud.workflow.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.DTO.UserMemoryUpdateDTO;
import com.ylcloud.DTO.SpaceFolderCreateDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.entity.FileRagChunk;
import com.ylcloud.entity.KnowledgeChatMessage;
import com.ylcloud.entity.UserMemoryItem;
import com.ylcloud.mapper.*;
import com.ylcloud.service.SpacePermissionService;
import com.ylcloud.service.SpaceService;
import com.ylcloud.service.SpaceFileService;
import com.ylcloud.service.memory.*;
import com.ylcloud.service.rag.retriever.RagMultiRouteRetriever;
import com.ylcloud.workflow.contract.WorkflowContracts.RiskLevel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.*;

/** TASK-006 首批 Tool 清单。所有业务访问都通过显式 Service/受约束 Mapper，并逐次校验用户权限。 */
@Configuration
public class WorkflowBuiltinToolConfiguration {
    @Bean WorkflowToolHandler conversationVerifyContext(KnowledgeChatSessionMapper sessions,
                                                         KnowledgeChatMessageMapper messages) {
        return tool("conversation.verify_context", RiskLevel.READ_ONLY, "tool.conversation.read", (ctx, args) -> {
            if (sessions.getActive(ctx.sessionId(), ctx.userId()) == null) throw new BaseException(403, "会话不可访问");
            Long messageId = optionalLong(args, "messageId");
            if (messageId != null && messages.getOwned(messageId, ctx.sessionId(), ctx.userId()) == null)
                throw new BaseException(403, "消息不可访问");
            return Map.of("valid", true, "sessionId", ctx.sessionId());
        });
    }

    @Bean WorkflowToolHandler knowledgeListAccessibleSpaces(SpaceService spaces) {
        return tool("knowledge.list_accessible_spaces", RiskLevel.READ_ONLY, "tool.knowledge.read", (ctx, args) ->
                Map.of("spaces", spaces.listMySpaces(ctx.userId()).stream().map(space -> Map.of(
                        "spaceId", space.getId(), "name", space.getName(), "role", space.getRole() == null ? "MEMBER" : space.getRole()
                )).toList()));
    }

    @Bean WorkflowToolHandler knowledgeSearch(SpacePermissionService permission, FileRagChunkMapper chunks,
                                                RagMultiRouteRetriever retriever) {
        return tool("knowledge.search", RiskLevel.READ_ONLY, "tool.knowledge.search", (ctx, args) -> {
            long spaceId = longArg(args, "spaceId"); String query = textArg(args, "query", 4_000);
            int limit = intArg(args, "limit", 1, 20, 8);
            permission.requireMember(spaceId, ctx.userId());
            List<FileRagChunk> active = chunks.listActiveBySpace(spaceId);
            List<Map<String, Object>> hits = retriever.retrieve(spaceId, query, active, limit, 0.0).stream().map(chunk ->
                    Map.<String, Object>of("chunkId", chunk.getId(), "hash", safeHash(chunk.getContentHash()),
                            "fileUuid", chunk.getFileUuid(), "chunkIndex", chunk.getChunkIndex())).toList();
            return Map.of("spaceId", spaceId, "query", query, "hits", hits);
        });
    }

    @Bean WorkflowToolHandler knowledgeLoadChunks(SpacePermissionService permission, FileRagChunkMapper chunks) {
        return tool("knowledge.load_chunks", RiskLevel.READ_ONLY, "tool.knowledge.read", (ctx, args) -> {
            long spaceId = longArg(args, "spaceId"); List<Long> ids = longList(args, "chunkIds", 100);
            permission.requireMember(spaceId, ctx.userId());
            List<FileRagChunk> loaded = chunks.listActiveBySpaceAndIds(spaceId, ids);
            return Map.of("spaceId", spaceId, "chunks", loaded.stream().map(chunk -> Map.of(
                    "chunkId", chunk.getId(), "hash", safeHash(chunk.getContentHash()), "version", 1,
                    "content", truncate(chunk.getContent(), 8_000))).toList());
        });
    }

    @Bean WorkflowToolHandler memorySearch(UserMemoryRetrievalService retrieval) {
        return tool("memory.search", RiskLevel.READ_ONLY, "tool.memory.read", (ctx, args) ->
                Map.of("query", textArg(args, "query", 4_000), "hits", retrieval.retrieve(ctx.userId(), textArg(args, "query", 4_000))
                        .stream().map(item -> memoryReference(item, false)).toList()));
    }

    @Bean WorkflowToolHandler memoryLoadVersions(UserMemoryItemMapper memories) {
        return tool("memory.load_versions", RiskLevel.READ_ONLY, "tool.memory.read", (ctx, args) ->
                Map.of("memories", memories.listOwnedVersions(ctx.userId(), longList(args, "memoryIds", 100))
                        .stream().map(item -> memoryReference(item, true)).toList()));
    }

    @Bean WorkflowToolHandler memorySave(UserMemoryService memories, KnowledgeChatMessageMapper messages) {
        return tool("memory.save", RiskLevel.WRITE, "tool.memory.write", (ctx, args) -> {
            long sourceMessageId = longArg(args, "sourceMessageId");
            KnowledgeChatMessage source = messages.getOwned(sourceMessageId, ctx.sessionId(), ctx.userId());
            if (source == null) throw new BaseException(403, "记忆来源消息不可访问");
            String content = textArg(args, "content", 2_000);
            UserMemoryItem item = memories.acceptManual(ctx.userId(), ctx.sessionId(), sourceMessageId,
                    "workflow:" + ctx.invocationId(), new UserMemoryCandidate(memoryType(args),
                            textArg(args, "key", 190), content, 1.0, true));
            if (item == null) throw new BaseException("记忆写入失败");
            memories.processIndex(item.getId());
            return Map.of("memoryId", item.getId(), "version", item.getVersion());
        });
    }

    @Bean WorkflowToolHandler memoryUpdate(UserMemoryManagementService memories, ObjectMapper objectMapper) {
        return tool("memory.update", RiskLevel.WRITE, "tool.memory.write", (ctx, args) -> {
            UserMemoryUpdateDTO dto = new UserMemoryUpdateDTO();
            dto.setMemoryType(memoryType(args)); dto.setContent(textArg(args, "content", 2_000));
            return nonNullMap(objectMapper.convertValue(memories.update(ctx.userId(), longArg(args, "memoryId"), dto),
                    new TypeReference<Map<String, Object>>() {}));
        });
    }

    @Bean WorkflowToolHandler memoryCleanup(UserMemoryManagementService memories, UserMemoryItemMapper mapper) {
        return tool("memory.enqueue_conflict_cleanup", RiskLevel.WRITE, "tool.memory.write", (ctx, args) -> {
            List<Long> ids = longList(args, "memoryIds", 100);
            ids.forEach(id -> {
                UserMemoryItem item = mapper.getOwned(id, ctx.userId());
                if (item == null || !("SUPERSEDED".equals(item.getMemoryStatus()) || "DELETE_PENDING".equals(item.getMemoryStatus())))
                    throw new BaseException(403, "只能清理已冲突或待删除记忆");
                memories.forget(ctx.userId(), id);
            });
            return Map.of("queued", ids.size());
        });
    }

    @Bean WorkflowToolHandler memoryDelete(UserMemoryManagementService memories) {
        return tool("memory.delete", RiskLevel.HIGH, "tool.memory.delete", (ctx, args) -> {
            long id = longArg(args, "memoryId"); memories.forget(ctx.userId(), id); return Map.of("deleted", true, "memoryId", id);
        });
    }

    @Bean WorkflowToolHandler memoryClear(UserMemoryManagementService memories) {
        return tool("memory.clear", RiskLevel.HIGH, "tool.memory.delete", (ctx, args) -> {
            memories.clear(ctx.userId()); return Map.of("cleared", true);
        });
    }

    @Bean WorkflowToolHandler knowledgeFileList(SpacePermissionService permission, SpaceFileMapper files) {
        return tool("knowledge.file.list", RiskLevel.READ_ONLY, "tool.knowledge.read", (ctx, args) -> {
            long spaceId = longArg(args, "spaceId"); permission.requireMember(spaceId, ctx.userId());
            return Map.of("spaceId", spaceId, "files", files.listAll(spaceId).stream().limit(500).map(file -> Map.of(
                    "fileId", file.getId(), "name", file.getFileName(), "directory", file.getDir() != null && file.getDir() == 1
            )).toList());
        });
    }

    @Bean WorkflowToolHandler knowledgeFileCreateFolder(SpaceFileService files, ObjectMapper objectMapper) {
        return tool("knowledge.file.create_folder", RiskLevel.WRITE, "tool.knowledge.write", (ctx, args) -> {
            SpaceFolderCreateDTO dto = new SpaceFolderCreateDTO(); dto.setName(textArg(args, "name", 255));
            dto.setParentId(args.get("parentId") == null ? null : longArg(args, "parentId"));
            return nonNullMap(objectMapper.convertValue(files.createFolder(longArg(args, "spaceId"), dto, ctx.userId()),
                    new TypeReference<Map<String, Object>>() {}));
        });
    }

    @Bean WorkflowToolHandler knowledgeFileDelete(SpaceFileService files) {
        return tool("knowledge.file.delete", RiskLevel.HIGH, "tool.knowledge.delete", (ctx, args) -> {
            long spaceId = longArg(args, "spaceId"); long fileId = longArg(args, "fileId");
            return Map.of("deleted", files.removeFile(spaceId, fileId, ctx.userId()), "spaceId", spaceId, "fileId", fileId);
        });
    }

    @Bean WorkflowToolHandler mockWebSearch(@Value("${ylcloud.workflow.tools.mock-external:true}") boolean mock) {
        return tool("web.search", RiskLevel.READ_ONLY, "tool.web.search", (ctx, args) -> {
            requireMock(mock); String query = textArg(args, "query", 2_000);
            return Map.of("provider", "mock", "query", query, "results", List.of(Map.of(
                    "title", "Mock search result", "url", "https://example.invalid/search", "snippet", "Deterministic mock result")));
        });
    }

    @Bean WorkflowToolHandler mockSmtp(@Value("${ylcloud.workflow.tools.mock-external:true}") boolean mock) {
        return tool("smtp.send_email", RiskLevel.HIGH, "tool.smtp.send", (ctx, args) -> {
            requireMock(mock); textArg(args, "to", 320); textArg(args, "subject", 500); textArg(args, "body", 20_000);
            return Map.of("provider", "mock", "accepted", true, "messageId", "mock-smtp-" + ctx.invocationId());
        });
    }

    @Bean WorkflowToolHandler mockCalDav(@Value("${ylcloud.workflow.tools.mock-external:true}") boolean mock) {
        return tool("caldav.create_event", RiskLevel.HIGH, "tool.caldav.write", (ctx, args) -> {
            requireMock(mock); textArg(args, "title", 500); textArg(args, "start", 64); textArg(args, "end", 64);
            return Map.of("provider", "mock", "created", true, "eventId", "mock-caldav-" + ctx.invocationId());
        });
    }

    private WorkflowToolHandler tool(String name, RiskLevel risk, String scope,
                                     java.util.function.BiFunction<ToolInvocationContext, Map<String, Object>, Map<String, Object>> action) {
        return new SimpleWorkflowToolHandler(name, risk, scope, action);
    }
    private static long longArg(Map<String,Object> args,String name) { Object v=args.get(name); if(!(v instanceof Number n)||n.longValue()<1) throw new BaseException(name+" 参数无效"); return n.longValue(); }
    private static Long optionalLong(Map<String,Object> args,String name) { return args.get(name)==null?null:longArg(args,name); }
    private static int intArg(Map<String,Object> args,String name,int min,int max,int fallback) { Object v=args.get(name); int n=v==null?fallback:v instanceof Number x?x.intValue():-1; if(n<min||n>max) throw new BaseException(name+" 参数无效"); return n; }
    private static String textArg(Map<String,Object> args,String name,int max) { Object v=args.get(name); if(!(v instanceof String s)||s.isBlank()||s.length()>max) throw new BaseException(name+" 参数无效"); return s.trim(); }
    private static String memoryType(Map<String,Object> args){String value=textArg(args,"memoryType",32).toUpperCase(Locale.ROOT);if(!value.matches("FACT|PREFERENCE|CONSTRAINT|DECISION"))throw new BaseException("memoryType 参数无效");return value;}
    private static List<Long> longList(Map<String,Object> args,String name,int max) { Object v=args.get(name); if(!(v instanceof List<?> list)||list.isEmpty()||list.size()>max) throw new BaseException(name+" 参数无效"); List<Long> ids=list.stream().map(item->{if(!(item instanceof Number n)||n.longValue()<1) throw new BaseException(name+" 参数无效"); return n.longValue();}).distinct().toList(); return ids; }
    private static String truncate(String value,int max){if(value==null)return "";return value.substring(0,Math.min(max,value.length()));}
    private static String safeHash(String value){return value!=null&&value.matches("[a-f0-9]{64}")?value:"0".repeat(64);}
    private static Map<String,Object> memoryReference(UserMemoryItem item,boolean content){Map<String,Object> result=new LinkedHashMap<>();result.put("memoryId",item.getId());result.put("version",item.getVersion());result.put("hash",safeHash(item.getContentHash()));if(content)result.put("content",item.getContent());return Map.copyOf(result);}
    private static Map<String,Object> nonNullMap(Map<String,Object> source){Map<String,Object> result=new LinkedHashMap<>();source.forEach((k,v)->{if(v!=null)result.put(k,v);});return Map.copyOf(result);}
    private static void requireMock(boolean enabled){if(!enabled)throw new BaseException(503,"真实外部 Provider 尚未配置");}
}
