package com.ylcloud.share;

import com.ylcloud.controller.ShareController;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named="SHARE_TEST_DB",matches=".+")
class ShareMigrationTest {
    @Test void upgradeArchivesOldRecordsAndRestoringDataDoesNotReopenOldRoutes() throws Exception {
        String original=System.getenv("SHARE_TEST_DB");
        if(!original.contains("/share_test?"))throw new IllegalArgumentException("Disposable schema required");
        var control=new JdbcTemplate(new DriverManagerDataSource(original,"root","share-test-local"));
        control.execute("DROP DATABASE IF EXISTS share_test_upgrade");
        control.execute("CREATE DATABASE share_test_upgrade");
        String url=original.replace("/share_test?","/share_test_upgrade?");
        var ds=new DriverManagerDataSource(url,"root","share-test-local");
        var db=new JdbcTemplate(ds);
        try{
            Flyway.configure().dataSource(ds).target("54").load().migrate();
            db.update("INSERT INTO file_share(share_code,user_file_id,file_uuid,owner_id,status) VALUES('legacy-code',1,'uuid',1,1)");
            Flyway.configure().dataSource(ds).load().migrate();
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM file_share",Integer.class));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM file_share_retired",Integer.class));
            Flyway.configure().dataSource(ds).load().migrate(); // repeat deployment validates
            db.update("INSERT INTO file_share SELECT * FROM file_share_retired");
            var mvc=MockMvcBuilders.standaloneSetup(new ShareController()).build();
            for(String suffix:new String[]{"","?preview=true","?stream=true","?download=true"}){
                mvc.perform(get("/api/share/legacy-code"+suffix)).andExpect(status().isOk())
                   .andExpect(content().string(org.hamcrest.Matchers.containsString("文件已过期")));
            }
        }finally{control.execute("DROP DATABASE share_test_upgrade");}
    }
}
