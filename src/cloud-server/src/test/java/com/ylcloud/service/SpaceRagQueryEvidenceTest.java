package com.ylcloud.service;

import com.ylcloud.service.space.SpaceFileLifecycleService;
import com.ylcloud.service.space.SpacePermissionService;
import com.ylcloud.service.space.SpaceRagService;
import com.ylcloud.DTO.SpaceRagQueryDTO;
import com.ylcloud.VO.SpaceRagQueryVO;
import com.ylcloud.config.RagProperties;
import com.ylcloud.constant.StatusConstant;
import com.ylcloud.entity.FileRagChunk;
import com.ylcloud.entity.FileRagParseResult;
import com.ylcloud.entity.SpaceRagConfig;
import com.ylcloud.entity.SpaceRagDocument;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.FileRagChunkMapper;
import com.ylcloud.mapper.FileRagParseResultMapper;
import com.ylcloud.mapper.SpaceFileMapper;
import com.ylcloud.mapper.SpaceRagChunkRefMapper;
import com.ylcloud.mapper.SpaceRagConfigLogMapper;
import com.ylcloud.mapper.SpaceRagDocumentMapper;
import com.ylcloud.mapper.SpaceRagMapper;
import com.ylcloud.mapper.SpaceRagQueryLogMapper;
import com.ylcloud.mapper.SpaceRagTaskMapper;
import com.ylcloud.service.rag.QdrantVectorStoreService;
import com.ylcloud.service.rag.RagChatResult;
import com.ylcloud.service.rag.RagChatService;
import com.ylcloud.service.rag.RagRerankService;
import com.ylcloud.service.rag.RagTaskExecutorService;
import com.ylcloud.service.rag.parser.DocumentParser;
import com.ylcloud.service.rag.parser.StructuredChunker;
import com.ylcloud.service.rag.query.QueryPlan;
import com.ylcloud.service.rag.query.QueryRewriteService;
import com.ylcloud.service.rag.retriever.RagMultiRouteRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpaceRagQueryEvidenceTest {
    private final SpaceRagMapper spaceRagMapper = mock(SpaceRagMapper.class);
    private final SpaceRagDocumentMapper documentMapper = mock(SpaceRagDocumentMapper.class);
    private final FileRagChunkMapper chunkMapper = mock(FileRagChunkMapper.class);
    private final SpaceRagQueryLogMapper queryLogMapper = mock(SpaceRagQueryLogMapper.class);
    private final SpacePermissionService permissionService = mock(SpacePermissionService.class);
    private final RagChatService chatService = mock(RagChatService.class);
    private final RagRerankService rerankService = mock(RagRerankService.class);
    private final RagMultiRouteRetriever retriever = mock(RagMultiRouteRetriever.class);
    private final QueryRewriteService queryRewriteService = mock(QueryRewriteService.class);
    private final SiteSettingService siteSettingService = mock(SiteSettingService.class);
    private final RagProperties properties = new RagProperties();
    private SpaceRagService service;
    private FileRagChunk chunk;
    private SpaceRagQueryDTO query;

    @BeforeEach
    void setUp() {
        service = new SpaceRagService(
                spaceRagMapper,
                documentMapper,
                chunkMapper,
                mock(SpaceRagChunkRefMapper.class),
                mock(SpaceRagTaskMapper.class),
                queryLogMapper,
                mock(SpaceRagConfigLogMapper.class),
                mock(SpaceFileMapper.class),
                mock(FileInfoMapper.class),
                permissionService,
                mock(QdrantVectorStoreService.class),
                chatService,
                rerankService,
                retriever,
                properties,
                mock(DocumentParser.class),
                mock(StructuredChunker.class),
                queryRewriteService,
                mock(RagTaskExecutorService.class),
                mock(KnowledgePipelineService.class),
                mock(KnowledgePipelineExecutorService.class),
                siteSettingService,
                mock(RagIndexTransactionService.class),
                mock(RagIndexConsistencyService.class),
                mock(SpaceFileLifecycleService.class)
        );

        SpaceRagConfig config = new SpaceRagConfig();
        config.setSpaceId(1L);
        config.setEnabled(StatusConstant.ENABLE);
        config.setTopK(5);
        config.setTemperature(BigDecimal.valueOf(0.2));
        config.setChatModel("test-model");
        when(spaceRagMapper.getBySpaceId(1L)).thenReturn(config);
        when(siteSettingService.getBoolean(SiteSettingService.LLM_ENABLED,true)).thenReturn(true);

        chunk = new FileRagChunk();
        chunk.setId(11L);
        chunk.setFileUuid("file-1");
        chunk.setFileHash("hash-1");
        chunk.setChunkIndex(0);
        chunk.setContent("候选上下文");
        when(chunkMapper.listActiveBySpace(1L)).thenReturn(List.of(chunk));

        QueryPlan plan = new QueryPlan();
        plan.setOriginal("测试问题");
        plan.setNormalized("测试问题");
        when(queryRewriteService.plan(anyString(),anyList())).thenReturn(plan);
        when(retriever.retrieve(anyLong(),any(QueryPlan.class),anyList(),anyInt(),isNull()))
                .thenReturn(List.of(chunk));
        when(rerankService.rerank(anyString(),anyList(),anyInt())).thenReturn(List.of(chunk));

        query = new SpaceRagQueryDTO();
        query.setQuestion("测试问题");
        query.setHistory(List.of());
    }

    @Test
    void noAnswerClearsCitationsContextsHitIdsAndLoggedEvidence() {
        when(chatService.answer(anyString(),anyList(),any(),anyList()))
                .thenReturn(RagChatResult.noAnswer("当前知识库中没有检索到足够的依据，无法回答该问题。"));

        SpaceRagQueryVO result = service.query(1L,query,7L);

        assertTrue(result.getCitations().isEmpty());
        assertTrue(result.getContexts().isEmpty());
        assertTrue(result.getHitChunkIds().isEmpty());
        assertEquals(List.of(11L),result.getRetrievedChunkIds());
        verify(documentMapper,never()).getBySpaceAndChunkId(anyLong(),anyLong());
        ArgumentCaptor<com.ylcloud.entity.SpaceRagQueryLog> logCaptor =
                ArgumentCaptor.forClass(com.ylcloud.entity.SpaceRagQueryLog.class);
        verify(queryLogMapper).insert(logCaptor.capture());
        assertEquals("",logCaptor.getValue().getHitChunkIds());
    }

    @Test
    void backfillsRetrievedOffsetsFromCachedParsedTextEvenWithoutAnAnswer() {
        FileRagParseResultMapper parseMapper = mock(FileRagParseResultMapper.class);
        service.setFileRagParseResultMapper(parseMapper);
        chunk.setMetadata("{\"pageStart\":4,\"pageEnd\":4,\"parserVersion\":\"structured-v3\"}");
        FileRagParseResult parsed = new FileRagParseResult();
        parsed.setFullText("前文候选上下文后文");
        parsed.setParseStatus("SUCCESS");
        when(parseMapper.getByFileAndVersion("file-1","hash-1","structured-v3")).thenReturn(parsed);
        when(chatService.answer(anyString(),anyList(),any(),anyList()))
                .thenReturn(RagChatResult.noAnswer("无法从当前知识库回答"));

        SpaceRagQueryVO result = service.query(1L,query,7L);

        assertTrue(result.getCitations().isEmpty());
        assertEquals(11L,result.getRetrievedEvidence().get(0).getChunkId());
        assertEquals(4,result.getRetrievedEvidence().get(0).getPage());
        assertEquals(2,result.getRetrievedEvidence().get(0).getOffsetStart());
        assertEquals(7,result.getRetrievedEvidence().get(0).getOffsetEnd());
    }

    @Test
    void modelUnavailableKeepsRetrievedCitationsForManualInspection() {
        chunk.setMetadata("{\"page\":12,\"offsetStart\":5,\"offsetEnd\":10}");
        when(chatService.answer(anyString(),anyList(),any(),anyList()))
                .thenReturn(RagChatResult.failed("模型暂不可用，请查看引用。","model unavailable"));
        SpaceRagDocument document = new SpaceRagDocument();
        document.setId(21L);
        document.setSpaceId(1L);
        document.setSpaceFileId(31L);
        document.setFileName("manual.md");
        when(documentMapper.getBySpaceAndChunkId(1L,11L)).thenReturn(document);

        SpaceRagQueryVO result = service.query(1L,query,7L);

        assertEquals(List.of(11L),result.getHitChunkIds());
        assertEquals(List.of("候选上下文"),result.getContexts());
        assertEquals(1,result.getCitations().size());
        assertEquals(11L,result.getCitations().get(0).getChunkId());
        assertEquals(12,result.getCitations().get(0).getPage());
        assertEquals(5,result.getCitations().get(0).getOffsetStart());
        assertEquals(10,result.getCitations().get(0).getOffsetEnd());
    }

    @Test
    void citationsOnlyContainTheTenChunksPassedToChat() {
        properties.getChat().setMaxContextChunks(20);
        SpaceRagConfig config = spaceRagMapper.getBySpaceId(1L);
        config.setTopK(20);
        query.setRetrievalMode("broad");
        List<FileRagChunk> candidates = new ArrayList<>();
        for(int i = 0; i < 12; i++) {
            candidates.add(positionedChunk((long) i + 1,i,"完整切片 " + i));
        }
        when(chunkMapper.listActiveBySpace(1L)).thenReturn(candidates);
        when(retriever.retrieve(anyLong(),any(QueryPlan.class),anyList(),anyInt(),isNull()))
                .thenReturn(candidates);
        when(rerankService.rerank(anyString(),anyList(),anyInt())).thenReturn(candidates);
        when(chatService.answer(anyString(),anyList(),any(),anyList()))
                .thenReturn(RagChatResult.success("回答。[1]"));

        SpaceRagQueryVO result = service.query(1L,query,7L);

        ArgumentCaptor<List<FileRagChunk>> passedChunks = ArgumentCaptor.forClass(List.class);
        verify(chatService).answer(anyString(),passedChunks.capture(),any(),anyList());
        List<Long> expectedIds = candidates.subList(0,10).stream().map(FileRagChunk::getId).toList();
        assertEquals(expectedIds,passedChunks.getValue().stream().map(FileRagChunk::getId).toList());
        assertEquals(expectedIds,result.getRetrievedChunkIds());
        assertEquals(expectedIds,result.getHitChunkIds());
        assertEquals(expectedIds,result.getCitations().stream().map(citation -> citation.getChunkId()).toList());
        assertEquals(10,result.getContexts().size());
    }

    @Test
    void chunkMetadataUsesJsonParsingInsteadOfWhitespaceSensitiveStringMatching() {
        String metadata = "{ \"parser\" : \"metadata\", \"fallback\" : true }";

        assertEquals("metadata",ReflectionTestUtils.invokeMethod(service,"metadataString",metadata,"parser"));
        assertEquals("true",ReflectionTestUtils.invokeMethod(service,"metadataString",metadata,"fallback"));
    }

    @Test
    void contextExpansionPreservesFusedRelevanceOrderInsteadOfDocumentPosition() {
        FileRagChunk mostRelevant = positionedChunk(101L,90,"标准化石定义");
        FileRagChunk earlierInDocument = positionedChunk(102L,2,"章节开头");
        QueryPlan plan = new QueryPlan();
        plan.setOriginal("什么是标准化石？");
        plan.setIntent("general");

        List<FileRagChunk> expanded = ReflectionTestUtils.invokeMethod(
                service,"expandContextChunks",List.of(mostRelevant,earlierInDocument),
                List.of(earlierInDocument,mostRelevant),plan,5);

        assertEquals(List.of(101L,102L),expanded.stream().map(FileRagChunk::getId).toList());
    }

    private FileRagChunk positionedChunk(Long id, int index, String content) {
        FileRagChunk value = new FileRagChunk();
        value.setId(id);
        value.setFileUuid("file-1");
        value.setFileHash("hash-1");
        value.setChunkIndex(index);
        value.setContent(content);
        return value;
    }
}
