package com.ylcloud.service;

import com.ylcloud.service.space.SpaceFileLifecycleService;
import com.ylcloud.service.space.SpacePermissionService;
import com.ylcloud.service.space.SpaceRagService;
import com.ylcloud.DTO.SpaceRagQueryDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.VO.RagAnswerEvidence;
import com.ylcloud.VO.SpaceRagCitationVO;
import com.ylcloud.VO.SpaceRagQueryVO;
import com.ylcloud.config.RagProperties;
import com.ylcloud.constant.StatusConstant;
import com.ylcloud.entity.FileRagChunk;
import com.ylcloud.entity.SpaceRagConfig;
import com.ylcloud.entity.SpaceRagDocument;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.FileRagChunkMapper;
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
import com.ylcloud.service.rag.RagEvidenceService;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    @Test void oldSuccessfulIndexWithoutVerifiableRevisionIsNotShownAsReady() {
        SpaceRagDocument document=new SpaceRagDocument(); document.setId(9L); document.setSpaceId(1L);
        document.setSpaceFileId(2L); document.setFileHash("hash-1"); document.setIndexStatus("SUCCESS");
        com.ylcloud.VO.SpaceRagDocumentVO view=ReflectionTestUtils.invokeMethod(service,"toDocumentVO",document);
        assertEquals("PENDING",view.getIndexStatus());
        assertTrue(view.getErrorMessage().contains("版本校验"));
    }

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
    private final RagEvidenceService evidenceService = mock(RagEvidenceService.class);
    private SpaceRagConfig config;
    private SpaceRagService service;
    private FileRagChunk chunk;
    private SpaceRagQueryDTO query;

    @BeforeEach
    void setUp() {
        RagProperties properties = new RagProperties();
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

        service.setEvidenceServices(evidenceService,mock(KnowledgeRevisionService.class));
        when(evidenceService.authorizedCandidates(anyLong(),anyLong(),anyList()))
                .thenAnswer(call -> call.getArgument(2));
        when(evidenceService.storeAnswer(anyLong(),anyList(),anyString(),anyList(),anyString())).thenReturn("answer-1");
        config = new SpaceRagConfig();
        config.setSpaceId(1L);
        config.setEnabled(StatusConstant.ENABLE);
        config.setTopK(5);
        config.setTemperature(BigDecimal.valueOf(0.2));
        config.setChatModel("test-model");
        config.setConfigRevision(1L);
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
    void modelUnavailableDoesNotExposeUnverifiedEvidence() {
        when(chatService.answer(anyString(),anyList(),any(),anyList()))
                .thenReturn(RagChatResult.failed("模型暂不可用，请查看引用。","model unavailable"));
        SpaceRagDocument document = new SpaceRagDocument();
        document.setId(21L);
        document.setSpaceId(1L);
        document.setSpaceFileId(31L);
        document.setFileName("manual.md");
        when(documentMapper.getBySpaceAndChunkId(1L,11L)).thenReturn(document);

        SpaceRagQueryVO result = service.query(1L,query,7L);

        assertTrue(result.getHitChunkIds().isEmpty());
        assertTrue(result.getContexts().isEmpty());
        assertTrue(result.getCitations().isEmpty());
        assertEquals("UNAVAILABLE",result.getAnswerStatus());
        verify(evidenceService,never()).createCitations(anyLong(),anyLong(),anyList(),anyList());
    }

    @Test
    void verifiedResultExposesOnlyTheActualEvidenceExcerpt() {
        chunk.setContent("资料中经验证的部分；后面这段没有送给模型。");
        RagAnswerEvidence evidence = new RagAnswerEvidence();
        evidence.setEvidenceId("e-11"); evidence.setChunkId(11L);
        evidence.setFileUuid("file-1"); evidence.setFileHash("hash-1");
        evidence.setContent("资料中经验证的部分");
        RagChatResult generated = RagChatResult.success("资料中经验证的部分。");
        generated.setVerified(true); generated.setEvidence(List.of(evidence));
        when(chatService.answer(anyString(),anyList(),any(),anyList())).thenReturn(generated);
        SpaceRagCitationVO citation = new SpaceRagCitationVO();
        citation.setEvidenceId("opaque-1"); citation.setChunkId(11L);
        when(evidenceService.createCitations(anyLong(),anyLong(),anyList(),anyList())).thenReturn(List.of(citation));

        SpaceRagQueryVO result = service.query(1L,query,7L);
        assertEquals(List.of("资料中经验证的部分"),result.getContexts());
        assertEquals(List.of(11L),result.getHitChunkIds());
        assertEquals("opaque-1",result.getCitations().get(0).getEvidenceId());
        assertEquals("answer-1",result.getAnswerId());
        verify(evidenceService).createCitations(1L,7L,List.of(chunk),List.of(evidence));
    }

    @Test
    void configChangeDuringGenerationRejectsAnswerBeforeEvidenceOrPersistence() {
        SpaceRagConfig changed = new SpaceRagConfig(); changed.setEnabled(1); changed.setConfigRevision(2L);
        when(spaceRagMapper.getBySpaceId(1L)).thenReturn(config,changed);
        when(chatService.answer(anyString(),anyList(),any(),anyList())).thenReturn(RagChatResult.success("旧策略回答"));
        assertThrows(BaseException.class,() -> service.query(1L,query,7L));
        verify(evidenceService,never()).createCitations(anyLong(),anyLong(),anyList(),anyList());
        verify(evidenceService,never()).storeAnswer(anyLong(),anyList(),anyString(),anyList(),anyString());
        verify(queryLogMapper,never()).insert(any());
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
