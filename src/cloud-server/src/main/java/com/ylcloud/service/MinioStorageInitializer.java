package com.ylcloud.service;

import com.ylcloud.utils.MinioclientUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MinioStorageInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(MinioStorageInitializer.class);

    private final MinioclientUtil minioClient;
    private final boolean initEnabled;
    private final boolean versioningEnabled;

    public MinioStorageInitializer(
            MinioclientUtil minioClient,
            @Value("${ylcloud.minio.init-enabled:true}") boolean initEnabled,
            @Value("${ylcloud.minio.versioning-enabled:true}") boolean versioningEnabled ) {
        this.minioClient = minioClient;
        this.initEnabled = initEnabled;
        this.versioningEnabled = versioningEnabled;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if(!initEnabled) {
            log.info("MinIO storage initialization skipped");
            return;
        }
        minioClient.ensureDefaultBucketReady(versioningEnabled);
        log.info("MinIO storage is ready, versioningEnabled={}",versioningEnabled);
    }
}
