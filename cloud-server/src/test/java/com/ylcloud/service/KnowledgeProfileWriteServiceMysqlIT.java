package com.ylcloud.service;

import com.ylcloud.Exception.ConflictException;
import com.ylcloud.entity.SpaceKnowledgeDocumentProfile;
import com.ylcloud.mapper.SpaceKnowledgeDocumentProfileMapper;
import com.ylcloud.mapper.SpaceKnowledgeQuestionMapper;
import com.ylcloud.service.knowledge.pipeline.KnowledgeSourceSnapshot;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@SpringJUnitConfig(KnowledgeProfileWriteServiceMysqlIT.Config.class)
@EnabledIfEnvironmentVariable(named = "YLCLOUD_PIPELINE_MYSQL_TEST", matches = "true")
class KnowledgeProfileWriteServiceMysqlIT {
    @Autowired
    private KnowledgeProfileWriteService service;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private FailureHarness failureHarness;
    private long spaceId;
    private long documentId;

    @BeforeEach
    void seedLegacyProfile() {
        spaceId = ThreadLocalRandom.current().nextLong(Long.MIN_VALUE / 2,-10_000L);
        documentId = spaceId + 1;
        jdbcTemplate.update("delete from space_knowledge_document_profile where space_id = ? and document_id = ?",
                spaceId,documentId);
        jdbcTemplate.update("insert into space_knowledge_document_profile(" +
                        "space_id, document_id, space_file_id, title, profile_status, source_chunk_ids, source_chunk_count, " +
                        "source_character_count, source_snapshot_signature, source_snapshot_revision, source_parser_version, " +
                        "status, createtime, updatetime) values(?,?,?,?,?,?,?,?,?,?,?,?,now(),now())",
                spaceId,documentId,documentId + 1,"Legacy profile","VALID","[\"1\"]",1,100,null,0L,
                "structured-v2",1);
        failureHarness.reset();
    }

    @AfterEach
    void cleanUp() {
        failureHarness.reset();
        jdbcTemplate.update("delete from space_knowledge_document_profile where space_id = ? and document_id = ?",
                spaceId,documentId);
    }

    @Test
    void legacyRevisionZeroProfileSynchronizesInRealMysql() {
        KnowledgeSourceSnapshot snapshot = snapshot("[\"11\",\"12\"]",2,320,"signature-a");

        SpaceKnowledgeDocumentProfile result = service.syncRetrievalSource(spaceId,documentId,snapshot,0L);

        assertEquals("signature-a",result.getSourceSnapshotSignature());
        assertEquals(1L,result.getSourceSnapshotRevision());
        assertDatabaseSnapshot("signature-a",1L);
    }

    @Test
    void concurrentIdenticalSnapshotsAreIdempotent() throws Exception {
        KnowledgeSourceSnapshot snapshot = snapshot("[\"11\",\"12\"]",2,320,"signature-a");
        List<Future<String>> results = runConcurrently(
                () -> service.syncRetrievalSource(spaceId,documentId,snapshot,0L).getSourceSnapshotSignature(),
                () -> service.syncRetrievalSource(spaceId,documentId,snapshot,0L).getSourceSnapshotSignature());

        assertEquals("signature-a",results.get(0).get());
        assertEquals("signature-a",results.get(1).get());
        assertDatabaseSnapshot("signature-a",1L);
    }

    @Test
    void concurrentDifferentSnapshotsAllowOneWriter() throws Exception {
        KnowledgeSourceSnapshot first = snapshot("[\"11\",\"12\"]",2,320,"signature-a");
        KnowledgeSourceSnapshot second = snapshot("[\"21\",\"22\"]",2,360,"signature-b");
        List<Future<String>> results = runConcurrently(
                () -> outcome(first),
                () -> outcome(second));

        List<String> outcomes = List.of(results.get(0).get(),results.get(1).get());
        assertEquals(1,outcomes.stream().filter(value -> value.startsWith("SUCCESS:")).count());
        assertEquals(1,outcomes.stream().filter("CONFLICT"::equals).count());
        String signature = jdbcTemplate.queryForObject(
                "select source_snapshot_signature from space_knowledge_document_profile where space_id = ? and document_id = ?",
                String.class,spaceId,documentId);
        assertTrue("signature-a".equals(signature) || "signature-b".equals(signature));
        assertDatabaseSnapshot(signature,1L);
    }

    @Test
    void runtimeFailureAfterUpdateRollsBackTransaction() {
        failureHarness.failNextReadAfterWrite();
        KnowledgeSourceSnapshot snapshot = snapshot("[\"11\",\"12\"]",2,320,"signature-a");

        assertThrows(IllegalStateException.class,
                () -> service.syncRetrievalSource(spaceId,documentId,snapshot,0L));

        assertDatabaseSnapshot(null,0L);
        Integer count = jdbcTemplate.queryForObject(
                "select source_chunk_count from space_knowledge_document_profile where space_id = ? and document_id = ?",
                Integer.class,spaceId,documentId);
        assertEquals(1,count);
    }

    private String outcome(KnowledgeSourceSnapshot snapshot) {
        try {
            return "SUCCESS:" + service.syncRetrievalSource(spaceId,documentId,snapshot,0L).getSourceSnapshotSignature();
        } catch (ConflictException ex) {
            return "CONFLICT";
        }
    }

    private List<Future<String>> runConcurrently(ThrowingSupplier first, ThrowingSupplier second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> firstResult = executor.submit(() -> awaitAndRun(ready,start,first));
            Future<String> secondResult = executor.submit(() -> awaitAndRun(ready,start,second));
            assertTrue(ready.await(10,TimeUnit.SECONDS),"concurrent workers did not become ready");
            start.countDown();
            return List.of(firstResult,secondResult);
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(30,TimeUnit.SECONDS),"concurrent workers did not finish");
        }
    }

    private String awaitAndRun(CountDownLatch ready, CountDownLatch start, ThrowingSupplier supplier) throws Exception {
        ready.countDown();
        assertTrue(start.await(10,TimeUnit.SECONDS),"concurrent start timed out");
        return supplier.get();
    }

    private void assertDatabaseSnapshot(String signature, long revision) {
        SnapshotRow row = jdbcTemplate.queryForObject(
                "select source_snapshot_signature, source_snapshot_revision from space_knowledge_document_profile " +
                        "where space_id = ? and document_id = ?",
                (rs,rowNum) -> new SnapshotRow(rs.getString(1),rs.getLong(2)),spaceId,documentId);
        assertNotNull(row);
        assertEquals(signature,row.signature());
        assertEquals(revision,row.revision());
    }

    private KnowledgeSourceSnapshot snapshot(String ids, int count, int characters, String signature) {
        return new KnowledgeSourceSnapshot(ids,count,characters,"structured-v2",signature);
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        String get() throws Exception;
    }

    private record SnapshotRow(String signature, long revision) {
    }

    static final class FailureHarness {
        private final AtomicBoolean failNextReadAfterWrite = new AtomicBoolean();
        private final ThreadLocal<Boolean> wroteSnapshot = ThreadLocal.withInitial(() -> false);

        void markWrite() {
            wroteSnapshot.set(true);
        }

        void failNextReadAfterWrite() {
            failNextReadAfterWrite.set(true);
        }

        boolean consumeFailure() {
            return wroteSnapshot.get() && failNextReadAfterWrite.compareAndSet(true,false);
        }

        void reset() {
            failNextReadAfterWrite.set(false);
            wroteSnapshot.remove();
        }
    }

    private record MapperDelegate(SpaceKnowledgeDocumentProfileMapper mapper) {
    }

    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean
        DataSource dataSource() {
            String url = requiredEnvironment("YLCLOUD_PIPELINE_MYSQL_URL");
            String user = requiredEnvironment("YLCLOUD_PIPELINE_MYSQL_USER");
            String password = requiredEnvironment("YLCLOUD_PIPELINE_MYSQL_PASSWORD");
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
            dataSource.setUrl(url);
            dataSource.setUsername(user);
            dataSource.setPassword(password);
            return dataSource;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            return factory.getObject();
        }

        @Bean
        MapperDelegate mapperDelegate(SqlSessionFactory sqlSessionFactory) throws Exception {
            MapperFactoryBean<SpaceKnowledgeDocumentProfileMapper> factory =
                    new MapperFactoryBean<>(SpaceKnowledgeDocumentProfileMapper.class);
            factory.setSqlSessionFactory(sqlSessionFactory);
            factory.afterPropertiesSet();
            return new MapperDelegate(factory.getObject());
        }

        @Bean
        FailureHarness failureHarness() {
            return new FailureHarness();
        }

        @Bean
        SpaceKnowledgeDocumentProfileMapper profileMapper(MapperDelegate delegate, FailureHarness harness) {
            return (SpaceKnowledgeDocumentProfileMapper) Proxy.newProxyInstance(
                    SpaceKnowledgeDocumentProfileMapper.class.getClassLoader(),
                    new Class<?>[]{SpaceKnowledgeDocumentProfileMapper.class},
                    (proxy,method,args) -> {
                        if("getByDocumentId".equals(method.getName()) && harness.consumeFailure()) {
                            throw new IllegalStateException("Injected read failure after snapshot update");
                        }
                        try {
                            Object result = method.invoke(delegate.mapper(),args);
                            if("syncRetrievalSource".equals(method.getName()) && result instanceof Integer rows && rows > 0) {
                                harness.markWrite();
                            }
                            return result;
                        } catch (InvocationTargetException ex) {
                            throw ex.getTargetException();
                        }
                    });
        }

        @Bean
        KnowledgeProfileWriteService knowledgeProfileWriteService(SpaceKnowledgeDocumentProfileMapper profileMapper) {
            return new KnowledgeProfileWriteService(profileMapper,mock(SpaceKnowledgeQuestionMapper.class),
                    mock(KnowledgeProfileAssetService.class));
        }

        private static String requiredEnvironment(String name) {
            String value = System.getenv(name);
            if(value == null || value.isBlank()) {
                throw new IllegalStateException(name + " must be set for MySQL acceptance tests");
            }
            return value;
        }
    }
}
