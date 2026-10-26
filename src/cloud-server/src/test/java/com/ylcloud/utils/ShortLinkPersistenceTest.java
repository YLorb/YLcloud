package com.ylcloud.utils;

import com.ylcloud.Exception.NotFoundException;
import com.ylcloud.controller.PreviewLinkController;
import com.ylcloud.entity.User;
import com.ylcloud.mapper.LoginMapper;
import com.ylcloud.service.AccessControlService;
import com.ylcloud.service.AuthorizationService;
import com.ylcloud.service.PreviewLinkService;
import com.ylcloud.service.space.SpaceFileAccessService;
import com.ylcloud.share.ShareCrypto;
import com.ylcloud.share.ShareLinkService;
import com.ylcloud.share.ShareRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static com.ylcloud.share.ShareModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Run only against a disposable short_link_test schema. */
@EnabledIfEnvironmentVariable(named = "SHORT_LINK_TEST_DB", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShortLinkPersistenceTest {
    private JdbcTemplate db;
    private TransactionTemplate tx;
    private ShareRepository repository;
    private PreviewLinkService previews;

    private ShortLinkUtil generator() {
        return new ShortLinkUtil(17, Clock.fixed(Instant.parse("2026-10-26T00:00:00Z"), ZoneOffset.UTC));
    }

    @BeforeAll
    void migrate() {
        String url = System.getenv("SHORT_LINK_TEST_DB");
        if (!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/short_link_test(?:\\?.*)?")) {
            throw new IllegalArgumentException("Disposable short_link_test schema required");
        }
        var source = new DriverManagerDataSource(url, "root", "short-link-test-only");
        db = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        Flyway.configure().dataSource(source).target("65").load().migrate();
        db.update("INSERT INTO share_link(creator_id,source_type,source_id,short_code,created_at,updated_at) VALUES(1,'PERSONAL',101,'11aaaaaa',UTC_TIMESTAMP(),UTC_TIMESTAMP())");
        Flyway.configure().dataSource(source).load().migrate();
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM share_link WHERE short_code='11aaaaaa'", Integer.class));
        assertEquals(11, db.queryForObject("SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='share_link' AND column_name='short_code'", Integer.class));
        assertEquals("ascii_bin", db.queryForObject("SELECT COLLATION_NAME FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='share_link' AND column_name='short_code'", String.class));
        repository = new ShareRepository(db);
    }

    @BeforeEach
    void reset() {
        db.update("DELETE FROM preview_short_link");
        db.update("DELETE FROM share_link_visit");
        db.update("DELETE FROM share_link");
        db.update("DELETE FROM user_file");
        db.update("INSERT INTO user_file(id,file_name,is_dir,user_id,parent_id) VALUES(101,'folder',1,1,0),(102,'other',1,1,0)");
        previews = new PreviewLinkService(db, generator());
    }

    @Test
    void shareCreationRetriesCollisionAndKeepsExistingAddresses() {
        LoginMapper users = mock(LoginMapper.class);
        User user = new User(); user.setId(1L); user.setStatus(1); user.setAccountStatus("ACTIVE");
        when(users.getById(1L)).thenReturn(user);
        var service = new ShareLinkService(repository, new ShareCrypto("test-only-secret-with-at-least-32-bytes"), tx,
                mock(AuthorizationService.class), mock(SpaceFileAccessService.class), users,
                mock(AccessControlService.class), mock(MinioclientUtil.class), db, generator(), mock(com.ylcloud.service.ShortLinkAccessLog.class));
        String collision = generator().generate(code -> true);
        db.update("INSERT INTO share_link(creator_id,source_type,source_id,short_code,created_at,updated_at) VALUES(2,'PERSONAL',102,?,UTC_TIMESTAMP(),UTC_TIMESTAMP())", collision);
        Conditions conditions = new Conditions("PERMANENT", null, null, null, false, null, false, null);
        View created = service.create(1, new Create("PERSONAL", 101L, null, conditions));
        assertNotEquals(collision, created.shortCode());
        assertTrue(created.shortCode().matches("[A-Za-z0-9]{9,11}"));
        assertEquals(created.shortCode(), service.create(1, new Create("PERSONAL", 101L, null, conditions)).shortCode());
        assertEquals("READY", service.open(created.shortCode(), null, "127.0.0.1").state());
        assertEquals(2, db.queryForObject("SELECT algorithm_version FROM share_link WHERE id=?", Integer.class, created.id()));
        db.update("UPDATE share_link SET short_code='11aaaaaa' WHERE id=?", created.id());
        assertEquals("READY", service.open("11aaaaaa", null, "127.0.0.1").state());
        assertEquals("11aaaaaa", service.create(1, new Create("PERSONAL", 101L, null, conditions)).shortCode());
    }

    @Test
    void previewMappingSurvivesRestartRetriesCollisionAndPreservesFragments() throws Exception {
        String first = previews.shorten("/api/file/preview/file-1/stream?parentId=0");
        var restarted = new PreviewLinkService(db, generator());
        String target = "/api/space/1/rag/evidence/e-1/preview#page=2";
        String second = restarted.shorten(target);
        assertNotEquals(first, second);
        assertEquals(second, previews.shorten(target));
        assertEquals(target, restarted.resolve(second.substring(3)));
        assertThrows(com.ylcloud.service.ShortLinkAbuseGuard.Missing.class, () -> restarted.resolve("unknown"));
        assertThrows(com.ylcloud.service.ShortLinkAbuseGuard.Missing.class, () -> restarted.resolve("../file"));
        var mvc = MockMvcBuilders.standaloneSetup(new PreviewLinkController(restarted, mock(com.ylcloud.service.ShortLinkAccessLog.class))).build();
        mvc.perform(get("/api/preview-links/" + second.substring(3)))
                .andExpect(status().isFound()).andExpect(header().string("Location", target))
                .andExpect(header().string("Cache-Control", "private, no-store"));
    }

    @Test
    void concurrentTargetCreationUsesOneMappingIncludingInsideTransactions() throws Exception {
        var executor = Executors.newFixedThreadPool(8);
        try {
            var tasks = IntStream.range(0, 32).mapToObj(i -> (Callable<String>) () -> tx.execute(status ->
                    previews.shorten("/api/space/1/files/101/preview/stream"))).toList();
            var codes = new HashSet<String>();
            for (var future : executor.invokeAll(tasks)) codes.add(future.get());
            assertEquals(1, codes.size());
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM preview_short_link", Integer.class));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void previewCannotRedirectOutsideSupportedReadEndpoints() {
        for (String target : new String[]{"https://evil.invalid", "//evil.invalid", "/api/admin/users",
                "/api/space/1/files/2/download", "/api/space/1/files/2/preview\r\nLocation: https://evil.invalid"}) {
            assertThrows(IllegalArgumentException.class, () -> previews.shorten(target));
        }
    }

    @Test
    void nodeLeaseRejectsDuplicateAndFencesExpiredOwner() {
        var first = new com.ylcloud.service.ShortLinkNodeLease(db, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), 19);
        var second = new com.ylcloud.service.ShortLinkNodeLease(db, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), 19);
        first.acquire();
        try {
            assertThrows(IllegalStateException.class, second::acquire);
            db.update("UPDATE short_link_node SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE node_id=19");
            second.acquire();
            first.renew();
            assertThrows(com.ylcloud.Exception.BaseException.class, first::requireActive);
            assertDoesNotThrow(second::requireActive);
            first.release(); // A stale owner must not delete its successor's lease.
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM short_link_node WHERE node_id=19", Integer.class));
        } finally { first.release(); second.release(); }
    }
}
