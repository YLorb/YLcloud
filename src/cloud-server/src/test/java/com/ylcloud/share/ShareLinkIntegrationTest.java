package com.ylcloud.share;

import com.ylcloud.authorization.*;
import com.ylcloud.entity.User;
import com.ylcloud.mapper.LoginMapper;
import com.ylcloud.service.*;
import com.ylcloud.service.space.SpaceFileAccessService;
import com.ylcloud.utils.MinioclientUtil;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipInputStream;
import static com.ylcloud.share.ShareModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="SHARE_TEST_DB",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShareLinkIntegrationTest {
    JdbcTemplate db;ShareRepository repo;ShareLinkService service;MinioclientUtil minio;
    LoginMapper users;AuthorizationService auth;SpaceFileAccessService space;
    @BeforeAll void schema() throws Exception {
        String url=System.getenv("SHARE_TEST_DB");
        if(!url.contains("/share_test"))throw new IllegalArgumentException("Only disposable share_test database allowed");
        var ds=new DriverManagerDataSource(url,"root","share-test-local");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        db=new JdbcTemplate(ds);repo=new ShareRepository(db);
        users=mock(LoginMapper.class);auth=mock(AuthorizationService.class);space=mock(SpaceFileAccessService.class);
        minio=new MinioclientUtil("http://127.0.0.1:19090","sharetest","share-test-local","share-test");
        minio.ensureDefaultBucketReady(true);
        service=new ShareLinkService(repo,new ShareCrypto("test-only-secret-with-at-least-32-bytes"),
            new TransactionTemplate(new DataSourceTransactionManager(ds)),auth,space,users,mock(AccessControlService.class),minio,db);
    }
    @BeforeEach void seed() throws Exception {
        db.update("DELETE FROM share_link_visit");db.update("DELETE FROM share_link");
        db.update("DELETE FROM user_file");db.update("DELETE FROM space_file");db.update("DELETE FROM file_version");db.update("DELETE FROM file_info");
        reset(users,auth,space);
        User u=new User();u.setId(1L);u.setStatus(1);u.setAccountStatus("ACTIVE");u.setRole("USER");
        when(users.getById(anyLong())).thenReturn(u);
        db.update("INSERT INTO file_info(file_uuid,name,size) VALUES('share-one','one.txt',5),('share-two','two.txt',3)");
        db.update("INSERT INTO user_file(id,file_name,file_uuid,is_dir,user_id,parent_id) VALUES(101,'one.txt','share-one',0,1,0),(102,'folder',NULL,1,1,0),(103,'child.txt','share-two',0,1,102)");
        minio.putObject(new ByteArrayInputStream("hello".getBytes()),5,"text/plain","share-one");
        minio.putObject(new ByteArrayInputStream("zip".getBytes()),3,"text/plain","share-two");
    }
    Conditions conditions(Long limit,boolean password,boolean force) {
        return new Conditions("PERMANENT",null,null,limit,password,password?"secret":null,force,null);
    }
    View create(long source,Long limit) {return service.create(1,new Create("PERSONAL",source,null,conditions(limit,false,false)));}
    @Test void reuseAndMutationKeepAddressAndCounts() throws Exception {
        View a=create(101,10L);assertEquals(a.id(),create(101,10L).id());
        Open open=service.open(a.shortCode(),1L,"127.0.0.1");
        try(var download=service.approve(a.shortCode(),open.downloadToken())) {
            ByteArrayOutputStream out=new ByteArrayOutputStream();service.transfer(download,out);assertEquals("hello",out.toString());
        }
        a=service.list(1L,1,20).items().get(0);
        View changed=service.update(1,a.id(),new Conditions("KEEP",null,null,1L,false,null,false,a.version()));
        assertEquals(a.shortCode(),changed.shortCode());assertEquals(1,changed.downloadCount());assertEquals("EXPIRED",changed.state());
        assertNotEquals(a.id(),create(101,10L).id());
    }
    @Test void lastDownloadCannotBeApprovedTwiceConcurrently() throws Exception {
        View link=create(101,1L);String token=service.open(link.shortCode(),null,"127.0.0.1").downloadToken();
        ExecutorService pool=Executors.newFixedThreadPool(8);CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> work=new ArrayList<>();
            for(int i=0;i<8;i++)work.add(pool.submit(()->{start.await();try(var d=service.approve(link.shortCode(),token)){return true;}catch(Expired e){return false;}}));
            start.countDown();int passed=0;for(var f:work)if(f.get(30,TimeUnit.SECONDS))passed++;
            assertEquals(1,passed);assertEquals(1L,repo.byId(link.id(),false).getDownloadCount());
        }finally{pool.shutdownNow();}
    }
    @Test void passwordsAndConditionVersionPreventBypass() throws Exception {
        View l=service.create(1,new Create("PERSONAL",101L,null,conditions(null,true,false)));
        Open gate=service.open(l.shortCode(),null,"::1");assertNull(gate.name());assertNull(gate.downloadToken());
        assertEquals("PASSWORD_REQUIRED",service.verify(l.shortCode(),new Verify("wrong",gate.visitToken())).state());
        Open ready=service.verify(l.shortCode(),new Verify("secret",gate.visitToken()));assertEquals("READY",ready.state());
        service.update(1,l.id(),new Conditions("KEEP",null,null,null,true,"new",false,l.version()));
        assertThrows(Expired.class,()->service.approve(l.shortCode(),ready.downloadToken()));
        assertEquals("PASSWORD_REQUIRED",service.open(l.shortCode(),null,"::1").state());
        assertThrows(IllegalArgumentException.class,()->service.create(1,new Create("PERSONAL",101L,null,conditions(null,true,true))));
    }
    @Test void revokedDeletedAndReplacedLinksHaveDistinctRecovery() {
        View l=create(101,null);
        db.update("UPDATE user_file SET status=2 WHERE id=101");
        assertEquals("EXPIRED",service.open(l.shortCode(),null,"ip").state());
        db.update("UPDATE user_file SET status=1 WHERE id=101");
        assertEquals("READY",service.open(l.shortCode(),null,"ip").state());
        db.update("UPDATE user_file SET file_uuid='share-two' WHERE id=101");
        db.update("UPDATE user_file SET file_uuid='share-one' WHERE id=101");
        assertEquals("EXPIRED",service.open(l.shortCode(),null,"ip").state());
        View fresh=create(101,null);service.revoke(1,fresh.id());
        assertEquals("EXPIRED",service.open(fresh.shortCode(),null,"ip").state());
    }
    @Test void zipIsDynamicAndExcludesReplacedChildren() throws Exception {
        View l=create(102,null);
        db.update("INSERT INTO user_file(id,file_name,file_uuid,is_dir,user_id,parent_id) VALUES(104,'新增.txt','share-one',0,1,102)");
        db.update("UPDATE user_file SET file_uuid='share-one' WHERE id=103");
        Open open=service.open(l.shortCode(),null,"ip");assertTrue(open.directory());
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(var d=service.approve(l.shortCode(),open.downloadToken())){service.transfer(d,out);}
        try(var zip=new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            assertEquals("新增.txt",zip.getNextEntry().getName());assertEquals("hello",new String(zip.readAllBytes()));assertNull(zip.getNextEntry());
        }
        assertEquals(1L,repo.byId(l.id(),false).getDownloadCount());
    }
    @Test void logsIncludeUnknownCodesAndRetentionDoesNotResetCounts() throws Exception {
        View l=create(101,null);
        service.open("unknown",null,"::1");
        var opened=service.open(l.shortCode(),1L,"ip");
        assertEquals(2,repo.visits(null,1,20).total());
        try(var d=service.approve(l.shortCode(),opened.downloadToken())){}
        db.update("UPDATE share_link_visit SET visited_at=? WHERE requested_code='unknown'",LocalDateTime.now(ZoneOffset.UTC).minusDays(31));
        service.cleanup();assertEquals(1,repo.visits(null,1,20).total());assertEquals(1L,repo.byId(l.id(),false).getDownloadCount());
    }
    @Test void permissionLossAndRecoveryAndManagerBoundary() {
        View l=create(101,null);
        when(auth.require(any(),any(),any(),any())).thenThrow(new com.ylcloud.Exception.ForbiddenException("denied"));
        assertEquals("EXPIRED",service.open(l.shortCode(),null,"ip").state());
        reset(auth);assertEquals("READY",service.open(l.shortCode(),null,"ip").state());
        assertThrows(com.ylcloud.Exception.ForbiddenException.class,()->service.revoke(2,l.id()));
    }
    @Test void caseSensitiveUniqueCodeAndCollisionRollback() {
        View l=create(101,null);
        db.update("UPDATE share_link SET short_code='Abcaaaaa' WHERE id=?",l.id());
        assertNotNull(repo.byCode("Abcaaaaa"));assertNull(repo.byCode("abcaaaaa"));
        long next=l.id()+100;
        db.execute("ALTER TABLE share_link AUTO_INCREMENT="+next);
        db.update("UPDATE share_link SET short_code=? WHERE id=?",ShareCrypto.code(next,1),l.id());
        assertThrows(IllegalArgumentException.class,()->create(102,null));
        assertEquals(1,repo.count(null));
    }
    @Test void expiryExtensionAndConcurrentCreatePreserveOneRecord() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(4);
        try {
            var work=new ArrayList<Future<View>>();
            for(int i=0;i<4;i++)work.add(pool.submit(()->create(101,null)));
            Set<Long> ids=new HashSet<>();for(var f:work)ids.add(f.get(30,TimeUnit.SECONDS).id());
            assertEquals(1,ids.size());View l=service.list(1L,1,20).items().get(0);
            l=service.update(1,l.id(),new Conditions("EXTEND",7,null,null,false,null,false,l.version()));
            OffsetDateTime expiry=OffsetDateTime.parse(l.expiresAt());
            l=service.update(1,l.id(),new Conditions("EXTEND",7,null,null,false,null,false,l.version()));
            assertEquals(expiry.plusDays(7),OffsetDateTime.parse(l.expiresAt()));
            db.update("UPDATE share_link SET expires_at=? WHERE id=?",LocalDateTime.now(ZoneOffset.UTC).minusDays(1),l.id());
            assertEquals("EXPIRED",service.open(l.shortCode(),null,"ip").state());
            View renewed=service.update(1,l.id(),new Conditions("EXTEND",7,null,null,false,null,false,l.version()));
            assertEquals("ACTIVE",renewed.state());
        }finally{pool.shutdownNow();}
    }
    @Test void versionReplacementWithoutUuidChangeInvalidatesLink() {
        View link=create(101,null);
        db.update("INSERT INTO file_version(file_uuid,version_no,minio_version_id,file_name,created_by,is_current,status,createtime) VALUES('share-one',2,'version-two','one.txt',1,1,1,NOW())");
        assertEquals("EXPIRED",service.open(link.shortCode(),null,"ip").state());
    }
    @Test void alreadyApprovedDownloadKeepsOriginalObjectVersion() throws Exception {
        View link=create(101,null);
        Open opened=service.open(link.shortCode(),null,"ip");
        try(var d=service.approve(link.shortCode(),opened.downloadToken())){
            minio.putObject(new ByteArrayInputStream("new".getBytes()),3,"text/plain","share-one");
            db.update("UPDATE file_info SET size=3,hash='new' WHERE file_uuid='share-one'");
            ByteArrayOutputStream out=new ByteArrayOutputStream();service.transfer(d,out);
            assertEquals("hello",out.toString());
        }
        assertEquals("EXPIRED",service.open(link.shortCode(),null,"ip").state());
    }
    @Test void spacesRequireTheirOwnEditingPermissionAndCannotSpoofSpace() {
        db.update("INSERT INTO space_file(id,space_id,file_uuid,file_name,is_dir,parent_id,status,created_by,createtime,updatetime) VALUES(201,9,'share-one','space.txt',0,0,1,1,NOW(),NOW())");
        View l=service.create(1,new Create("SPACE",201L,9L,conditions(null,false,false)));
        verify(space,atLeastOnce()).requireNodeAction(9L,201L,1L,SpaceFileAction.SHARE);
        assertThrows(IllegalArgumentException.class,()->service.create(1,new Create("SPACE",201L,10L,conditions(null,false,false))));
        when(space.requireNodeAction(9L,201L,1L,SpaceFileAction.SHARE)).thenThrow(new com.ylcloud.Exception.ForbiddenException("denied"));
        assertEquals("EXPIRED",service.open(l.shortCode(),null,"ip").state());
    }
    @Test void interruptedTransferKeepsApprovedCountAndDeletesManifest() throws Exception {
        View l=create(102,1L);Open opened=service.open(l.shortCode(),null,"ip");
        var d=service.approve(l.shortCode(),opened.downloadToken());
        try(d) {
            assertThrows(IOException.class,()->service.transfer(d,new OutputStream(){
                @Override public void write(int b) throws IOException {throw new IOException("simulated disconnect");}
            }));
        }
        assertFalse(java.nio.file.Files.exists(d.manifest()));
        assertEquals(1L,repo.byId(l.id(),false).getDownloadCount());
        assertEquals("EXPIRED",service.open(l.shortCode(),null,"ip").state());
    }
    @Test void concurrentConditionEditAndApprovalPreserveCountAndVersion() throws Exception {
        View l=create(101,2L);String credential=service.open(l.shortCode(),null,"ip").downloadToken();
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            var approval=pool.submit(()->{start.await();try(var d=service.approve(l.shortCode(),credential)){return 1L;}catch(Expired e){return 0L;}});
            var edit=pool.submit(()->{start.await();return service.update(1,l.id(),new Conditions("KEEP",null,null,1L,false,null,false,l.version()));});
            start.countDown();long count=approval.get(30,TimeUnit.SECONDS);View updated=edit.get(30,TimeUnit.SECONDS);
            assertEquals(count,repo.byId(l.id(),false).getDownloadCount());
            assertEquals(l.shortCode(),updated.shortCode());
            assertThrows(IllegalArgumentException.class,()->service.update(1,l.id(),new Conditions("KEEP",null,null,1L,false,null,false,l.version())));
        } finally {pool.shutdownNow();}
    }
    @Test void largeZipStreamsThroughSmallManifest() throws Exception {
        long bytes=Long.getLong("share.test.bytes",64L*1024*1024);
        InputStream generated=new InputStream(){
            long remaining=bytes;
            public int read(){if(remaining--<=0)return -1;return 65;}
            public int read(byte[] b,int off,int len){if(remaining<=0)return -1;int n=(int)Math.min(len,remaining);Arrays.fill(b,off,off+n,(byte)65);remaining-=n;return n;}
        };
        minio.putObject(generated,bytes,"application/octet-stream","share-large");
        db.update("INSERT INTO file_info(file_uuid,name,size) VALUES('share-large','large.bin',?)",bytes);
        db.update("INSERT INTO user_file(id,file_name,file_uuid,is_dir,user_id,parent_id) VALUES(110,'large.bin','share-large',0,1,102)");
        View l=create(102,null);Open opened=service.open(l.shortCode(),null,"ip");
        long started=System.nanoTime();
        try(var d=service.approve(l.shortCode(),opened.downloadToken())){
            assertTrue(java.nio.file.Files.size(d.manifest())<4096);
            service.transfer(d,OutputStream.nullOutputStream());
        }
        System.out.println("SHARE_PERF bytes="+bytes+" maxHeap="+Runtime.getRuntime().maxMemory()+" zipMs="+((System.nanoTime()-started)/1_000_000));
        assertEquals(1L,repo.byId(l.id(),false).getDownloadCount());
    }
}
